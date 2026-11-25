package ro.alex.minuta

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val DEFAULT_CLAUDE_MODEL = "claude-sonnet-5-5"
    const val DEFAULT_STT_MODEL = "gpt-4o-transcribe"

    private fun p(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun anthropicKey(ctx: Context) = p(ctx).getString("anthropic_key", "").orEmpty().trim()
    fun openAiKey(ctx: Context) = p(ctx).getString("openai_key", "").orEmpty().trim()
    fun claudeModel(ctx: Context) =
        p(ctx).getString("claude_model", "").orEmpty().trim().ifEmpty { DEFAULT_CLAUDE_MODEL }
    fun sttModel(ctx: Context) =
        p(ctx).getString("stt_model", "").orEmpty().trim().ifEmpty { DEFAULT_STT_MODEL }
    fun context(ctx: Context) = p(ctx).getString("context", "").orEmpty().trim()

    fun hasKeys(ctx: Context) = anthropicKey(ctx).isNotEmpty() && openAiKey(ctx).isNotEmpty()

    fun save(
        ctx: Context,
        anthropicKey: String,
        openAiKey: String,
        claudeModel: String,
        sttModel: String,
        context: String,
    ) {
        p(ctx).edit()
            .putString("anthropic_key", anthropicKey.trim())
            .putString("openai_key", openAiKey.trim())
            .putString("claude_model", claudeModel.trim())
            .putString("stt_model", sttModel.trim())
            .putString("context", context.trim())
            .apply()
    }
}
