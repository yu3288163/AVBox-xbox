package com.github.avbox.osc.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.avbox.osc.R
import com.github.avbox.osc.ui.base.BaseActivity
import com.github.avbox.osc.io.cancelLocalConfig
import com.github.avbox.osc.io.handleLocalConfigResult
import com.github.avbox.osc.io.handleLocalSourceTreeResult
import com.github.avbox.osc.io.LocalScriptImporter
import com.github.avbox.osc.io.importLocalSource
import com.github.avbox.osc.io.startLocalConfig
import com.github.avbox.osc.ui.components.SheetHostScaffold
import com.github.avbox.osc.api.AppBootstrap
import com.github.avbox.osc.ui.page.ConfigManageScreen
import com.github.avbox.osc.ui.page.ConfigManageViewModel
import com.github.avbox.osc.ui.theme.AVBoxTheme
import com.github.avbox.osc.ui.theme.enableTransparentEdgeToEdge
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.PermissionHelper
import com.github.avbox.osc.util.SpiderStorageNotice

class ConfigManageActivity : BaseActivity() {

    companion object {
        private const val EXTRA_OPEN_REPO = "extra_open_repo"

        /** [openRepo] = true 时进入页面后直接展开「换仓」面板，供首页订阅源弹窗跳转用。 */
        @JvmStatic
        @JvmOverloads
        fun start(context: Context, openRepo: Boolean = false) {
            context.startActivity(
                Intent(context, ConfigManageActivity::class.java).apply {
                    if (openRepo) putExtra(EXTRA_OPEN_REPO, true)
                },
            )
        }
    }

    private val configVm by lazy {
        ViewModelProvider(this, ConfigManageViewModel.defaultFactory())[ConfigManageViewModel::class.java]
    }

    private val localConfigLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                handleLocalConfigResult(this, uri) { needTree -> if (needTree) settleUnreachableSource(uri) }
            } else {
                cancelLocalConfig()
            }
        }

    private val sourceTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            handleLocalSourceTreeResult(this, uri)
        }

    fun launchLocalConfig(onResult: (name: String, api: String) -> Unit) {
        startLocalConfig(localConfigLauncher) { name, api -> onResult(name, api) }
    }

    private var folderPickCallback: ((String, String) -> Unit)? = null

    private val folderPickLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val callback = folderPickCallback
            folderPickCallback = null
            if (uri == null || callback == null) return@registerForActivityResult
            // SAF 只给 content:// 读权限，不能靠 File(path).listFiles() 扫原始目录。
            // 这里用 DocumentsContract 枚举并同步到应用私有目录，回调的是可直接扫的目录。
            lifecycleScope.launch(Dispatchers.IO) {
                val folder = LocalScriptImporter.sync(applicationContext, uri)
                withContext(Dispatchers.Main) {
                    if (folder == null) {
                        Toast.makeText(
                            this@ConfigManageActivity,
                            getString(R.string.toast_local_tree_denied),
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        callback(folder.path, folder.name)
                    }
                }
            }
        }

    /** 选一个脚本目录，回调 [私有目录路径, 用户看到的目录名]（用于“添加脚本”） */
    fun launchFolderPick(onPicked: (path: String, name: String) -> Unit) {
        folderPickCallback = onPicked
        try {
            folderPickLauncher.launch(null)
        } catch (th: Throwable) {
            folderPickCallback = null
            AppLog.e("ConfigManageActivity", th)
            Toast.makeText(this, getString(R.string.toast_local_tree_denied), Toast.LENGTH_LONG).show()
        }
    }

    private fun launchSourceTree() {
        SpiderStorageNotice.suppress()
        sourceTreeLauncher.launch(null)
    }

    private fun settleUnreachableSource(uri: Uri) {
        if (PermissionHelper.isStorageGranted(this)) {
            launchSourceTree()
            return
        }
        PermissionHelper.requestStorage(this) { granted, _ ->
            if (granted.isNullOrEmpty()) {
                launchSourceTree()
            } else {
                handleLocalConfigResult(this, uri) { needTree -> if (needTree) launchSourceTree() }
            }
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppBootstrap.start()
        if (savedInstanceState == null) handleViewIntent(intent)
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                leavePage()
            }
        })
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    ConfigManageScreen(
                        onNavigateBack = { leavePage() },
                        initialOpenRepo = intent.getBooleanExtra(EXTRA_OPEN_REPO, false),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        importLocalSource(
            this,
            uri,
            onImported = { name, api ->
                if (configVm.addLocalSource(name, api)) {
                    Toast.makeText(this, R.string.toast_local_source_already_imported, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.toast_local_source_imported, name), Toast.LENGTH_SHORT).show()
                }
            },
            onFailed = { unsupported ->
                val tip = if (unsupported) {
                    R.string.toast_local_source_unsupported
                } else {
                    R.string.toast_local_config_read_failed
                }
                Toast.makeText(this, getString(tip), Toast.LENGTH_SHORT).show()
            },
        )
    }

    private fun inForeignTask(): Boolean {
        val tasks = try {
            (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.appTasks
        } catch (th: Throwable) {
            AppLog.e("ConfigManageActivity", th)
            null
        } ?: return false
        return tasks.none { it.taskInfo?.taskId == taskId }
    }

    private fun leavePage() {
        if (inForeignTask()) {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }
        finish()
    }
}
