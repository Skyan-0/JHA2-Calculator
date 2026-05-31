Proguard optimization rules to keep database and UI models secure
-keep class com.jha2.calculator.** { ; }
-keep class androidx.room.RoomDatabase { ; }
-dontwarn androidx.room.
