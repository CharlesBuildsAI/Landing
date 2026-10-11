package com.crit.landing;

import java.util.Locale;

/**
 * Recognises well-known AI assistant apps so setup can list them first.
 * This is only a sort hint: any installed app can be picked as Home.
 * To add an assistant, add its package name (or its exact app label) below.
 */
final class AiApps {
    private AiApps() { }

    private static final String[] PACKAGES = {
            "com.openai.chatgpt",                  // ChatGPT
            "com.anthropic.claude",                // Claude
            "com.google.android.apps.bard",        // Gemini
            "ai.perplexity.app.android",           // Perplexity
            "com.microsoft.copilot",               // Microsoft Copilot
            "ai.x.grok",                           // Grok
            "com.deepseek.chat",                   // DeepSeek
            "ai.mistral.chat",                     // Le Chat
            "com.meta.ai",                         // Meta AI
            "ai.inflection.pi",                    // Pi
    };

    private static final String[] LABELS = {
            "chatgpt", "claude", "gemini", "perplexity", "copilot", "grok", "deepseek",
            "le chat", "meta ai", "pi", "hark", "poe", "character.ai",
    };

    static boolean isAi(String pkg, CharSequence label) {
        for (String p : PACKAGES) if (p.equals(pkg)) return true;
        String l = label == null ? "" : label.toString().trim().toLowerCase(Locale.US);
        for (String s : LABELS) if (s.equals(l)) return true;
        return false;
    }
}
