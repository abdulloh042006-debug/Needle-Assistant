package com.needleassistant.app.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.util.Locale

internal class ContactActions(private val context: Context) {
    fun execute(command: String): String? {
        val action = parse(command) ?: return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "Kontaktlarni topish uchun Needle sozlamalarida Kontaktlar ruxsatini yoqing."
        }
        val contacts = find(action.name)
        if (contacts.isEmpty()) return "${action.name} nomli kontakt topilmadi."
        val exact = contacts.filter { normalize(it.first) == normalize(action.name) }
        val candidates = (if (exact.isNotEmpty()) exact else contacts).distinctBy { it.second }
        if (candidates.size > 1) {
            return "Bir nechta mos kontakt bor: " + candidates.take(5).joinToString("; ") { (name, number) ->
                "$name (…${number.filter(Char::isDigit).takeLast(4)})"
            } + ". Aniq kontakt ismini ayting."
        }
        val (name, number) = candidates.first()
        return when (action.type) {
            Type.CALL -> call(name, number)
            Type.SMS -> sendSms(name, number, action.message)
        }
    }

    private fun call(name: String, number: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "Qo'ng'iroq uchun Telefon ruxsati kerak. Needle sozlamalarida uni yoqing."
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "$name ga qo'ng'iroq boshlandi."
        } catch (_: SecurityException) {
            "Qo'ng'iroq qilishga ruxsat berilmadi. Telefon ruxsatini tekshiring."
        } catch (_: Exception) {
            "Telefon ilovasini ishga tushirib bo'lmadi."
        }
    }

    private fun sendSms(name: String, number: String, message: String?): String {
        if (message.isNullOrBlank()) return "$name uchun xabar matnini ayting. Masalan: \"${name}ga SMS yoz: Salom, yaxshimisan?\""
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "SMS yuborish uchun SMS ruxsati kerak. Needle sozlamalarida uni yoqing."
        }
        return try {
            @Suppress("DEPRECATION")
            SmsManager.getDefault().sendTextMessage(number, null, message, null, null)
            "$name ga SMS yuborish operator tarmog'iga topshirildi. Yetkazilganini operator tasdiqlaydi."
        } catch (_: SecurityException) {
            "SMS yuborishga ruxsat berilmadi. SMS ruxsatini tekshiring."
        } catch (error: Exception) {
            "SMS yuborilmadi: ${error.localizedMessage ?: "noma'lum xatolik"}"
        }
    }

    private fun find(query: String): List<Pair<String, String>> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val found = mutableListOf<Pair<String, String>>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
        ) ?: return emptyList()
        cursor.use {
            val nameColumn = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberColumn = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameColumn).orEmpty()
                val number = it.getString(numberColumn).orEmpty()
                if (name.isNotBlank() && number.isNotBlank() && normalize(name).contains(normalize(query))) {
                    found += name to number
                }
            }
        }
        return found
    }

    private fun parse(command: String): Action? {
        val sms = Regex("""(?iu)^(.+?)\\s+(?:sms|xabar)\\s+(?:yoz|yubor)(?:\\s*[:,-]\\s*(.*))?$""").matchEntire(command.trim())
        if (sms != null) {
            val name = stripSuffix(sms.groupValues[1])
            if (name.isNotBlank()) return Action(name, Type.SMS, sms.groupValues.getOrNull(2)?.trim()?.takeIf(String::isNotBlank))
        }
        val call = Regex("""(?iu)^(.+?)\\s+(?:telefon qil|qo'ng'iroq qil|qongiroq qil|qo'ng'iroq|qongiroq)$""").matchEntire(command.trim())
            ?: return null
        val name = stripSuffix(call.groupValues[1])
        return name.takeIf(String::isNotBlank)?.let { Action(it, Type.CALL) }
    }

    private fun stripSuffix(value: String): String =
        value.trim().replace(Regex("""(?iu)(ga|ka|qa)$"""), "").trim()

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^\\p{L}\\p{N}]+"""), " ")
            .trim()

    private enum class Type { CALL, SMS }
    private data class Action(val name: String, val type: Type, val message: String? = null)
}
