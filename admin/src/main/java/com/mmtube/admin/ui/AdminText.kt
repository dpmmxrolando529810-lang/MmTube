package com.mmtube.admin.ui

import android.content.Context
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import java.util.Locale

/**
 * English / Burmese support for the admin app.
 *
 * Admin screens were written with Burmese literals. Instead of moving every
 * sentence into resources, Compose [Text] below translates the string it
 * renders when English is selected. Interpolated strings (numbers, user codes)
 * are handled by the regex rules, so dynamic messages are translated too.
 */
object AdminI18n {
    private const val PREFS = "mmtube_admin_ui"
    private const val KEY = "english"

    var english by mutableStateOf(false)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        english = prefs.getBoolean(KEY, Locale.getDefault().language != "my")
    }

    fun toggle(context: Context) {
        english = !english
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, english).apply()
    }

    fun translate(input: String): String {
        if (!english || input.isEmpty()) return input
        exact[input]?.let { return it }
        for ((regex, format) in rules) {
            val match = regex.matchEntire(input) ?: continue
            var out = format
            for (i in 1..match.groupValues.lastIndex) {
                out = out.replace("{$i}", match.groupValues[i])
            }
            return out
        }
        return input
    }

    private val exact: Map<String, String> = mapOf(
        "  ကိုယ်ပိုင်အစမ်းပို့ပါ" to "  Send a private test",
        "  ကြေညာချက် ပို့ပါ" to "  Send announcement",
        "+1 လ" to "+1 month",
        "Activation မှတ်တမ်း မရှိသေးပါ။" to "No activation history yet.",
        "Dry-run (ကိုယ့်ကိုပဲ အစမ်းမြင်ရန်)" to "Dry run (only you can see it)",
        "Firestore `announcements/latest` ထဲ သိမ်းမယ် — app ထဲ banner အနေနဲ့ ပေါ်မယ် (user တိုင်းမြင်ရမယ်)" to "Saved to `announcements/latest` in Firestore — shown as an in-app banner to every user",
        "Pending payment မရှိသေးပါ" to "No pending payments",
        "ကူးယူရန်" to "Copy",
        "ငွေမမြင်ရသေး" to "No revenue yet",
        "စက်" to "Sep",
        "ဇန်" to "Jan",
        "ဇူ" to "Jul",
        "ဇွန်" to "Jun",
        "တိုးမယ်" to "Extend",
        "ဒီ" to "Dec",
        "နို" to "Nov",
        "နောက်တစ်ကြိမ် ထပ်ပို့ပါ" to "Send again",
        "နှိပ်ပြီး Payments tab သို့ ရောက်မယ်" to "Tap to open the Payments tab",
        "ပမာဏ မှား" to "Wrong amount",
        "ပိတ်ရန်" to "Close",
        "ပုံစံ စာကို ပို့ရန်" to "Send the template message",
        "ပုံမရှင်း" to "Unclear screenshot",
        "ဖုန်း / User ID / Claim # ရှာရန်" to "Search phone / User ID / Claim #",
        "ဖေ" to "Feb",
        "မတိုးဘူး" to "Don't extend",
        "မတ်" to "Mar",
        "မေ" to "May",
        "မှတ်ချက် (optional)" to "Note (optional)",
        "မှတ်ချက် (reason / note — မှတ်တမ်းထဲပါမယ်)" to "Reason / note (saved in the log)",
        "ယနေ့" to "Today",
        "ယနေ့ ကုန်မည်" to "Expires today",
        "ယနေ့ဝင်ငွေ" to "Today's revenue",
        "လစဉ် ဝင်ငွေ (Ks)" to "Monthly revenue (Ks)",
        "လစာ" to "Monthly",
        "ဝင်ငွေ သတိပေးချက်" to "Revenue warning",
        "သတ်မှတ်ထားသော အချိန်အတွင်း ကုန်မည့် user မရှိသေးပါ။" to "No users expire in the selected period yet.",
        "သြ" to "Aug",
        "အောက်" to "Oct",
        "ဧပြီ" to "Apr",
        "↩ ပြန်အမ်း" to "↩ Refund",
        "+1 လ အမြန်တိုး" to "+1 month quick extend",
        "Admin မှ အတင်း sign-out" to "Force sign-out by admin",
        "Admin သာမြင်မည် (dry-run)" to "Admin-only (dry run)",
        "Email နဲ့ password ဖြည့်ပါ" to "Enter email and password",
        "Email သို့မဟုတ် password မှားနေတယ်" to "Wrong email or password",
        "ကြေညာချက် ပို့ပြီးပါပြီ" to "Announcement sent",
        "ကြေညာချက်စာသား ရေးပါ" to "Write the announcement text",
        "ငွေပြန်အမ်း (မှတ်တမ်းမှ)" to "Refund (from log)",
        "ဒီ email နဲ့ account မရှိပါဘူး — Main app မှာ အကောင့်ဆောက်ပါ" to "No account with this email — create one in the main app",
        "ပြန်ပြောင်းပြီးပါပြီ" to "Refunded",
        "၅ ရက်ကျော်သွား — auto ငြင်းပယ်" to "Over 5 days — auto-declined",
        "✦ Dry-run ကြေညာချက် ပို့ပြီး" to "✦ Dry-run announcement sent",
        "✦ ကြေညာချက် ပို့ပြီးပါပြီ" to "✦ Announcement sent",
    )

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("^(.+) — ငွေပြန်အမ်းပြီးပါပြီ\$") to "{1} — Already refunded",
        Regex("^(.+) — ငွေပြန်အမ်းပြီး\$") to "{1} — Refunded",
        Regex("^(.+) — ငြင်းပယ်ပြီးပါပြီ\$") to "{1} — Already declined",
        Regex("^(\\d+) ခု ငြင်းပယ်ပြီး\$") to "{1} declined",
        Regex("^(\\d+) အောင်မြင်, (\\d+) ပျက်\$") to "{1} succeeded, {2} failed",
        Regex("^(.+) ကို sign-out လုပ်ပြီး\$") to "Signed out {1}",
        Regex("^Sign in မအောင်မြင်ဘူး \\((.*)\\)\$") to "Sign-in failed ({1})",
        Regex("^ငွေပေးချေမှု (\\d+) ခု စစ်ဆေးရန် ကျန်နေပါသည်\$") to "{1} payment(s) awaiting review",
        Regex("^ငွေပေးချေမှု (\\d+) ခု အတည်ပြုပြီး\$") to "{1} payment(s) approved",
        Regex("^↩ (.+) — ပြန်ပြောင်း\$") to "↩ {1} — Refunded",
        Regex("^✓ (\\d+) ခု အတည်ပြု \\((\\d+) fail\\)\$") to "✓ {1} approved ({2} failed)",
        Regex("^✗ (\\d+) ခု ငြင်းပယ် \\((\\d+) fail\\)\$") to "✗ {1} declined ({2} failed)",
        Regex("^✗ (.+) — ငြင်းပယ်\$") to "✗ {1} — Declined",
        Regex("^(\\d+(?:\\.\\d+)?) ရက် ကျန်\$") to "{1} days left",
        Regex("^(\\d+(?:\\.\\d+)?) ရက် ကုန်ပြီး\$") to "{1} days ago",
        Regex("^(\\d+(?:\\.\\d+)?) ရက်\$") to "{1} days",
        Regex("^(.+) ကို app ထဲမှ sign-out ခိုင်းမှာ အမှန်ပဲလား\\?\$") to "Force sign-out for {1} in the app?",
        Regex("^(.+) မှာ (\\d+) ရက်ကျန်နေပါသေးတယ် — ဆက်တိုးမယ်လား\\?\$") to "{1} still has {2} days left — extend anyway?",
        Regex("^(\\d+) ခု\$") to "{1} items",
        Regex("^ရွေးထား (\\d+) ခု ကို ငြင်းပယ်ရန်\$") to "Decline {1} selected",
        Regex("^ရွေးထား (\\d+) ခု ကို အတည်ပြုရန်\$") to "Approve {1} selected",
        Regex("^သက်သေပုံ — (.+)\$") to "Screenshot — {1}",
    )
}

/** Translated replacement for material3 Text inside this package. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    androidx.compose.material3.Text(
        text = AdminI18n.translate(text),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style,
    )
}
