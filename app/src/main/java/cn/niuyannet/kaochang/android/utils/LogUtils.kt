package cn.niuyannet.kaochang.android.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import cn.niuyannet.kaochang.android.MyApp
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 日志工具类，支持将日志写入文件系统
 */
object LogUtils {
    private const val TAG = "KaoChang"
    
    // 日志级别
    const val LEVEL_VERBOSE = 1
    const val LEVEL_DEBUG = 2
    const val LEVEL_INFO = 3
    const val LEVEL_WARN = 4
    const val LEVEL_ERROR = 5
    const val LEVEL_NONE = 6

    // 当前日志级别，可通过配置修改
    private var currentLevel = LEVEL_VERBOSE
    
    // 是否将日志写入文件
    private var writeToFile = true
    
    // 日志文件保留天数
    private const val LOG_FILE_RETENTION_DAYS = 7
    
    // 单个日志文件最大大小（5MB）
    private const val MAX_FILE_SIZE = 20 * 1024//5 * 1024 * 1024
    
    // 日期格式
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    
    // 使用单线程执行器来写入文件，避免多线程并发写入问题
    private val logExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    // 当前日志文件
    private var currentLogFile: File? = null
    
    /**
     * 初始化日志系统
     */
    fun init(context: Context, level: Int = LEVEL_VERBOSE, writeToFile: Boolean = true) {
        this.currentLevel = level
        this.writeToFile = writeToFile
        
        if (writeToFile) {
            // 清理过期日志文件
            cleanOldLogFiles(context)
            // 确保日志目录存在
            getLogDir(context)
        }
    }
    
    /**
     * 获取日志目录
     */
    private fun getLogDir(context: Context): File {
        val logDir = File(context.getExternalFilesDir(null), "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        return logDir
    }
    
    /**
     * 获取当前日志文件
     */
    private fun getCurrentLogFile(context: Context): File {
        val today = dateFormat.format(Date())
        val logDir = getLogDir(context)
        
        // 如果当前日志文件不存在或者是不同的日期，则创建新文件
        if (currentLogFile == null || !currentLogFile!!.name.contains(today)) {
            val fileIndex = getNextFileIndex(logDir, today)
            currentLogFile = File(logDir, "${today}_${fileIndex}.log")
        }
        
        // 如果当前日志文件超过大小限制，则创建新文件
        if (currentLogFile!!.exists() && currentLogFile!!.length() > MAX_FILE_SIZE) {
            val fileIndex = getNextFileIndex(logDir, today)
            currentLogFile = File(logDir, "${today}_${fileIndex}.log")
        }
        
        return currentLogFile!!
    }
    
    /**
     * 获取下一个文件索引
     */
    private fun getNextFileIndex(logDir: File, today: String): Int {
        val existingLogs = logDir.listFiles { file ->
            file.name.startsWith(today) && file.name.endsWith(".log")
        } ?: return 1
        
        return existingLogs.size + 1
    }
    
    /**
     * 清理过期日志文件
     */
    private fun cleanOldLogFiles(context: Context) {
        logExecutor.execute {
            try {
                val logDir = getLogDir(context)
                val now = System.currentTimeMillis()
                val retentionTime = now - (LOG_FILE_RETENTION_DAYS * 24 * 60 * 60 * 1000L)
                
                logDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() < retentionTime) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean old log files", e)
            }
        }
    }
    
    /**
     * 将日志写入文件
     */
    private fun writeLogToFile(context: Context, level: String, tag: String, msg: String, tr: Throwable? = null) {
        if (!writeToFile) return
        
        logExecutor.execute {
            try {
                val logFile = getCurrentLogFile(context)
                val timestamp = timeFormat.format(Date())
                val logMessage = StringBuilder()
                    .append(timestamp)
                    .append(" ")
                    .append(level)
                    .append("/")
                    .append(tag)
                    .append(": ")
                    .append(msg)
                
                if (tr != null) {
                    logMessage.append("\n").append(Log.getStackTraceString(tr))
                }
                
                FileWriter(logFile, true).use { writer ->
                    writer.append(logMessage).append("\n")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write log to file", e)
            }
        }
    }
    
    /**
     * 导出日志文件
     * 返回包含所有日志的目录
     */
    fun getLogDirectory(context: Context): File {
        return getLogDir(context)
    }
    
    /**
     * 获取最新的日志文件
     */
    fun getLatestLogFile(context: Context): File? {
        val logDir = getLogDir(context)
        return logDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }
    }
    
    /**
     * 删除所有日志文件
     */
    fun clearAllLogs(context: Context) {
        val logDir = getLogDir(context)
        logExecutor.execute {
            logDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".log")) {
                    file.delete()
                }
            }
        }
    }
    
    // 各种日志级别的方法
    fun v(tag: String, msg: String) {
        if (currentLevel <= LEVEL_VERBOSE) {
            Log.v(tag, msg)
            writeLogToFile(MyApp.instance(), "V", tag, msg)
        }
    }
    
    fun d(tag: String, msg: String) {
        if (currentLevel <= LEVEL_DEBUG) {
            Log.d(tag, msg)
            writeLogToFile(MyApp.instance(), "D", tag, msg)
        }
    }
    
    fun i(tag: String, msg: String) {
        if (currentLevel <= LEVEL_INFO) {
            Log.i(tag, msg)
            writeLogToFile(MyApp.instance(), "I", tag, msg)
        }
    }
    
    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (currentLevel <= LEVEL_WARN) {
            if (tr != null) {
                Log.w(tag, msg, tr)
            } else {
                Log.w(tag, msg)
            }
            writeLogToFile(MyApp.instance(), "W", tag, msg, tr)
        }
    }
    
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (currentLevel <= LEVEL_ERROR) {
            if (tr != null) {
                Log.e(tag, msg, tr)
            } else {
                Log.e(tag, msg)
            }
            writeLogToFile(MyApp.instance(), "E", tag, msg, tr)
        }
    }
    
    // 便利方法，使用默认TAG
    fun v(msg: String) = v(TAG, msg)
    fun d(msg: String) = d(TAG, msg)
    fun i(msg: String) = i(TAG, msg)
    fun w(msg: String, tr: Throwable? = null) = w(TAG, msg, tr)
    fun e(msg: String, tr: Throwable? = null) = e(TAG, msg, tr)
} 