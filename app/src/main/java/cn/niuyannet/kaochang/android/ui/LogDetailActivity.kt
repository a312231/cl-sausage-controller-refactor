package cn.niuyannet.kaochang.android.ui

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import cn.niuyannet.kaochang.android.databinding.ActivityLogDetailBinding
import java.io.File

class LogDetailActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityLogDetailBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener {
             finish()
        }
        
        // 设置标题栏
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "日志详情"
        // 获取日志文件路径
        val logFilePath = intent.getStringExtra("log_file_path")
        if (logFilePath != null) {
            val file = File(logFilePath)
            if (file.exists()) {
                // 设置文件名
                binding.tvLogFileName.text = file.name
                
                // 读取文件内容并显示
                binding.tvLogContent.text = file.readText()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
} 