package com.example.rolechatmini.service;

import com.example.rolechatmini.service.RoleCatalogService.RoleCard;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SkillOrchestrator {

    public record PromptBuild(String systemMessage, List<String> assistantGuidelines) {}

    public PromptBuild build(RoleCard role, String userText) {
        List<String> guidelines = new ArrayList<>();
        // 世界观一致性
        guidelines.add("保持角色世界观与说话风格一致，避免越界到角色未知领域。");
        // 苏格拉底式提问
        if ("socrates".equals(role.id())) {
            guidelines.add("优先以提问引导用户澄清概念、寻找证据、比较替代方案。");
            guidelines.add("每次输出最多2-3个关键问题。");
        }
        // 引用与总结
        guidelines.add("在末尾附上要点清单（TL;DR），不超过3条。");
        // 情绪共情（简化）
        guidelines.add("根据用户语气选择同理或鼓励性的开场短句。");
        return new PromptBuild(role.systemPrompt(), guidelines);
    }
}
