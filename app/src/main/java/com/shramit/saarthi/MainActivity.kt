package com.shramit.saarthi

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

import android.net.Uri
import android.widget.VideoView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupVideoBackground()

        val launchCabBtn: Button = findViewById(R.id.launchCabBtn)
        launchCabBtn.setOnClickListener {
            val intent = Intent(this, CabActivity::class.java)
            startActivity(intent)
        }
        
        val openVaultBtn: Button = findViewById(R.id.openVaultBtn)
        openVaultBtn.setOnClickListener {
            // Placeholder for Open Identity Vault
        }
    }

    private fun setupVideoBackground() {
        val videoView: VideoView = findViewById(R.id.videoBackground)
        val videoPath = "android.resource://" + packageName + "/" + R.raw.chakra
        videoView.setVideoURI(Uri.parse(videoPath))
        
        videoView.setOnPreparedListener { mp ->
            mp.isLooping = true
            // Scale and center the video (simple approach for VideoView)
            val videoRatio = mp.videoWidth / mp.videoHeight.toFloat()
            val screenRatio = videoView.width / videoView.height.toFloat()
            val scaleX = videoRatio / screenRatio
            if (scaleX >= 1f) {
                videoView.scaleX = scaleX
            } else {
                videoView.scaleY = 1f / scaleX
            }
            videoView.start()
        }
    }
}
