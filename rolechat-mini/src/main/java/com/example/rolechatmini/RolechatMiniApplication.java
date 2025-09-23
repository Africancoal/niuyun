package com.example.rolechatmini;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;

@SpringBootApplication
public class RolechatMiniApplication {
    
    @Autowired
    private Environment environment;
    
    public static void main(String[] args) {
        SpringApplication.run(RolechatMiniApplication.class, args);
    }
    
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        String port = environment.getProperty("server.port", "9099");
        String contextPath = environment.getProperty("server.servlet.context-path", "");
        String baseUrl = "http://localhost:" + port + contextPath;
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🎭 RoleChat Mini - AI角色扮演语音聊天系统");
        System.out.println("=".repeat(80));
        System.out.println("✅ 应用启动成功！");
        System.out.println("🌐 访问地址: " + baseUrl);
        System.out.println("📱 主要功能:");
        System.out.println("   • 角色搜索与选择 (苏格拉底、哈利·波特、英语导师等)");
        System.out.println("   • 流式AI对话 (SSE实时响应)");
        System.out.println("   • 语音合成播放 (TTS)");
        System.out.println("   • 多技能AI角色 (世界观一致性、苏格拉底提问、要点总结等)");
        System.out.println("\n🔧 API接口:");
        System.out.println("   • GET  " + baseUrl + "/api/roles/search?q=关键词");
        System.out.println("   • POST " + baseUrl + "/api/chat/stream (SSE流式聊天)");
        System.out.println("   • POST " + baseUrl + "/api/chat/asr (语音识别)");
        System.out.println("   • POST " + baseUrl + "/api/chat/tts (语音合成)");
        System.out.println("\n💡 使用提示:");
        System.out.println("   1. 在浏览器中打开上述地址");
        System.out.println("   2. 搜索并选择感兴趣的角色");
        System.out.println("   3. 输入文本开始对话");
        System.out.println("   4. 点击'TTS播放'听AI回复");
        System.out.println("=".repeat(80) + "\n");
    }
}
