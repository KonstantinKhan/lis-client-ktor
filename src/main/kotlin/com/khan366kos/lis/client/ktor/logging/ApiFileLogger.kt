package com.khan366kos.lis.client.ktor.logging

import io.ktor.client.plugins.logging.Logger
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Диагностика реального инцидента: один и тот же код/settings.json/входные данные на разных
// серверах Loodsman ведут себя по-разному (на удалённом появляются объекты "Литье"/"Архив
// техпроцесса"/"Техоперация", которых нет в mapping.types[]) — нужен полный трейс HTTP-вызовов
// клиента на диск, чтобы отделить "это создал наш код" от "это сделал сам Loodsman/ПОЛИНОМ".
// synchronized(file) — запросы идут конкурентно (Client.requestGate это только троттлинг числа
// одновременных вызовов, не сериализация), без лока строки лога могут перемежаться/теряться.
// UTF-8 BOM в начале файла — без него Notepad и другие вьюеры на русской локали Windows
// определяют файл как ANSI/CP1251 и показывают кракозябры вместо кириллицы, хотя байты уже
// корректный UTF-8 (Kotlin File.appendText по умолчанию пишет UTF-8).
private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

fun fileLogger(fileName: String): Logger {
    val file = File(fileName)
    if (!file.exists()) {
        file.writeBytes(UTF8_BOM)
    }
    val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    return object : Logger {
        override fun log(message: String) {
            synchronized(file) {
                file.appendText("${LocalDateTime.now().format(timestampFormat)} $message\n", Charsets.UTF_8)
            }
        }
    }
}
