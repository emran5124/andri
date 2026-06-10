package com.example.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        ApiKeyConfig::class,
        PromptTemplate::class,
        ActiveSession::class,
        HistoryLog::class,
        AppSettings::class,
        ErrorLog::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun apiKeyDao(): ApiKeyDao
    abstract fun promptTemplateDao(): PromptTemplateDao
    abstract fun activeSessionDao(): ActiveSessionDao
    abstract fun historyLogsDao(): HistoryLogsDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun errorLogDao(): ErrorLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "summarizer_database"
                )
                .fallbackToDestructiveMigration()
                .addCallback(DatabaseCallback(scope))
                .build()
                INSTANCE = instance
                instance
            }
        }
    }

    private class DatabaseCallback(
        private val scope: CoroutineScope
    ) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                scope.launch(Dispatchers.IO) {
                    populateDatabase(database)
                }
            }
        }

        suspend fun populateDatabase(db: AppDatabase) {
            // Populate defaults
            db.appSettingsDao().insertSettings(AppSettings())

            db.promptTemplateDao().insertTemplate(
                PromptTemplate(
                    title = "خلاصه‌ساز ADHD (مخفف و رنگی)",
                    promptContent = """1. Language & Style
   - فقط به فارسی جواب بده. کوتاه، ساده، ساختارمند، و ADHD-friendly.
   - خیلی مهمه که چیزی از قلم نیوفته


3. Tone
   - محکم، واضح، Gen Z-friendly، صادق، بدون حرف اضافه.

4. ساختار خروجی — برای هر:
🚩[موضوع]🚩
محتوا

- only 🚩[موضوع]🚩 not 🚩[*موضوع*]🚩 and not 🚩*[موضوع]*🚩 or any style else.

5. روش خلاصهنویسی — مهمترین بخش
   - هدف: همون محتوا، بیان فشردهتر — نه حذف اطلاعات، فقط کوتاهتر گفتن.
   - حجم خروجی باید ۲۰ تا ۳۰ درصد متن اصلی باشه.
   - دیدی وقتی یه فایلو فشرده میکنی فایل همون فایله و خراب نشده و فقط حجمش کمتر میشه؟ اینم همینطور.
   - جملات رو بازنویسی کن.
   - از ساختار bullet point یا شمارهگذاری برای وضوح استفاده کن.
   - هیچوقت ننویس "در این بخش" یا "نویسنده میگوید".
   - هیچ نکته‌ای از شرح نباید از قلم بیوفته.

فقط خروجی ساختاریافته رو برگردون، بدون توضیح اضافه.
Text Color Styles:

[text] green
¥¥text¥¥ red
«text» yellow
<text> blue

Highlight Styles:

[[text]] green
((text)) red
««text»» yellow
<<text>> blue

Nested syntax must work correctly.

Examples:

**<<text>>**
<<**text**>>
<<[[text]]>>
[hello ((world)) text]

but useing emojis is in priority."""
                )
            )

            db.promptTemplateDao().insertTemplate(
                PromptTemplate(
                    title = "خلاصه جامع به زبان فارسی",
                    promptContent = "متن داده شده را به زبان فارسی بسیار روان، مفید و حرفه‌ای خلاصه‌سازی کن. تمام نکات کلیدی و جمع‌بندی نهایی را در ساختاری زیبا تنظیم نما."
                )
            )
            db.promptTemplateDao().insertTemplate(
                PromptTemplate(
                    title = "لیست نکات کلیدی (فارسی)",
                    promptContent = "نکات طلایی و خلاصه موضوعات مهم متن زیر را به صورت آیتم‌های بالت‌پوینت مجزا، خوانا و منظم به زبان فارسی بنویس."
                )
            )
        }
    }
}
