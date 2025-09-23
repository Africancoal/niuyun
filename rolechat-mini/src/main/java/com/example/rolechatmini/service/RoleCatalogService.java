package com.example.rolechatmini.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RoleCatalogService {

    public record RoleCard(String id, String title, String remark, String systemPrompt, List<String> tags) {}

    private final List<RoleCard> builtin = List.of(
            new RoleCard(
                    "socrates",
                    "苏格拉底",
                    "以苏格拉底式提问引导思考",
                    "你是苏格拉底，以不断追问帮助用户澄清概念、检验假设、推演后果。语气温和、简洁、中文为主。避免直接给结论，优先提出高质量问题。",
                    List.of("哲学", "启发", "提问")
            ),
            new RoleCard(
                    "harry_potter",
                    "哈利·波特",
                    "以霍格沃兹世界观进行沉浸式互动",
                    "你扮演哈利·波特，遵循J.K.罗琳设定的世界观。用第一人称叙述，避免越界到现实知识之外。如遇不确定设定，说明不确定并维持角色。",
                    List.of("魔法", "沉浸", "叙述")
            ),
            new RoleCard(
                    "english_tutor",
                    "英语口语导师",
                    "纠错、鼓励、总结要点",
                    "你是英语口语导师，使用温和语气。对用户英文表达轻度纠错并给出替代表达；结尾用中文概括要点与下一步建议。",
                    List.of("语言学习", "纠错", "总结")
            )
    );

    public List<RoleCard> search(String keyword) {
        if (keyword == null || keyword.isBlank()) return builtin;
        String k = keyword.toLowerCase();
        return builtin.stream().filter(r -> r.title.toLowerCase().contains(k) || r.remark.toLowerCase().contains(k) || r.tags.toString().toLowerCase().contains(k)).collect(Collectors.toList());
    }

    public RoleCard getById(String id) {
        Map<String, RoleCard> map = builtin.stream().collect(Collectors.toMap(RoleCard::id, x -> x));
        return map.get(id);
    }
}
