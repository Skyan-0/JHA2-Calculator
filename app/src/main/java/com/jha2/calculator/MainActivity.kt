package com.jha2.calculator

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import kotlin.math.*

// ==========================================
// 1. PERSISTENCE ENGINE (SQLite Room Schema)
// ==========================================

@Entity(tableName = "calculation_history")
data class HistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val expression: String,
    val result: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM calculation_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<HistoryItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: HistoryItem)

    @Delete
    suspend fun deleteHistory(item: HistoryItem)

    @Query("DELETE FROM calculation_history")
    suspend fun clearAllHistory()
}

@Database(entities = [HistoryItem::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "jha2_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ==========================================
// 2. MATHEMATICAL PARSER (Recursive Descent)
// ==========================================

class MathParser(private val input: String) {
    private var pos = -1
    private var ch = 0

    private fun nextChar() {
        ch = if (++pos < input.length) input[pos].code else -1
    }

    private fun eat(charToEat: Int): Boolean {
        while (ch == ' '.code) nextChar()
        if (ch == charToEat) {
            nextChar()
            return true
        }
        return false
    }

    fun parse(): Double {
        nextChar()
        val x = parseExpression()
        if (pos < input.length) throw ArithmeticException("Syntax error")
        return x
    }

    private fun parseExpression(): Double {
        var x = parseTerm()
        while (true) {
            if (eat('+'.code)) x += parseTerm()
            else if (eat('−'.code) || eat('-'.code)) x -= parseTerm()
            else break
        }
        return x
    }

    private fun parseTerm(): Double {
        var x = parseFactor()
        while (true) {
            if (eat('×'.code) || eat('*'.code)) x *= parseFactor()
            else if (eat('÷'.code) || eat('/'.code)) {
                val divisor = parseFactor()
                if (divisor == 0.0) throw ArithmeticException("Divide by zero")
                x /= divisor
            } else break
        }
        return x
    }

    private fun parseFactor(): Double {
        if (eat('+'.code)) return parseFactor()
        if (eat('−'.code) || eat('-'.code)) return -parseFactor()

        var x: Double
        val startPos = this.pos
        if (eat('('.code)) {
            x = parseExpression()
            eat(')'.code)
        } else if ((ch >= '0'.code && ch <= '9'.code) || ch == '.'.code) {
            while ((ch >= '0'.code && ch <= '9'.code) || ch == '.'.code) nextChar()
            x = input.substring(startPos, this.pos).toDouble()
        } else if ((ch >= 'a'.code && ch <= 'z'.code) || ch == '√'.code || ch == 'π'.code) {
            while ((ch >= 'a'.code && ch <= 'z'.code) || ch == '√'.code || ch == 'π'.code) nextChar()
            val func = input.substring(startPos, this.pos)
            if (func == "π") {
                x = PI
            } else if (func == "e") {
                x = E
            } else {
                x = parseFactor()
                x = when (func) {
                    "sin" -> sin(Math.toRadians(x))
                    "cos" -> cos(Math.toRadians(x))
                    "tan" -> {
                        val rad = Math.toRadians(x)
                        if (cos(rad) == 0.0) throw ArithmeticException("Tangent Undefined")
                        tan(rad)
                    }
                    "log" -> {
                        if (x <= 0) throw ArithmeticException("Domain Error")
                        log10(x)
                    }
                    "ln" -> {
                        if (x <= 0) throw ArithmeticException("Domain Error")
                        ln(x)
                    }
                    "sqrt", "√" -> {
                        if (x < 0) throw ArithmeticException("Domain Error")
                        sqrt(x)
                    }
                    "sqr" -> x * x
                    else -> throw ArithmeticException("Unknown function: $func")
                }
            }
        } else {
            throw ArithmeticException("Unexpected symbol: " + ch.toChar())
        }

        if (eat('^'.code)) x = x.pow(parseFactor())

        // Postfix Factorial evaluation
        while (eat('!'.code)) {
            x = factorial(x)
        }

        return x
    }

    private fun factorial(n: Double): Double {
        if (n < 0.0) throw ArithmeticException("Factorial of negative")
        if (n % 1.0 != 0.0) throw ArithmeticException("Factorial of decimal")
        var result = 1.0
        val limit = n.toInt()
        for (i in 1..limit) {
            result *= i
        }
        return result
    }
}

// ==========================================
// 3. ACTION SPECIFICATION SCHEMA
// ==========================================

sealed class CalculatorAction {
    data class Number(val value: String) : CalculatorAction()
    data class Operator(val op: String) : CalculatorAction()
    data class ScienceFunc(val func: String) : CalculatorAction()
    data class Memory(val type: String) : CalculatorAction()
    object Decimal : CalculatorAction()
    object Clear : CalculatorAction()
    object Delete : CalculatorAction()
    object Calculate : CalculatorAction()
    object ToggleSign : CalculatorAction()
    object Percentage : CalculatorAction()
    object ToggleSciencePanel : CalculatorAction()
}

// ==========================================
// 4. ARCHITECTURAL BUSINESS VIEWMODEL
// ==========================================

class CalculatorViewModel(private val dao: HistoryDao) : ViewModel() {
    private val _expression = MutableStateFlow("")
    val expression: StateFlow<String> = _expression.asStateFlow()

    private val _result = MutableStateFlow("0")
    val result: StateFlow<String> = _result.asStateFlow()

    private val _isScienceExpanded = MutableStateFlow(false)
    val isScienceExpanded: StateFlow<Boolean> = _isScienceExpanded.asStateFlow()

    private val _isHapticEnabled = MutableStateFlow(true)
    val isHapticEnabled: StateFlow<Boolean> = _isHapticEnabled.asStateFlow()

    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    val history = dao.getAllHistory()

    private var memoryValue: Double = 0.0
    private val formatter = DecimalFormat("#.########")

    fun onAction(action: CalculatorAction) {
        when (action) {
            is CalculatorAction.Number -> {
                _expression.value += action.value
                evaluateLivePreview()
            }
            is CalculatorAction.Operator -> {
                val current = _expression.value
                if (current.isNotEmpty() && !current.last().toString().matches(Regex("[+−×÷^]"))) {
                    _expression.value += action.op
                }
            }
            is CalculatorAction.ScienceFunc -> {
                _expression.value += "${action.func}("
            }
            is CalculatorAction.Decimal -> {
                val current = _expression.value
                val lastToken = current.split(Regex("[+−×÷^()]")).lastOrNull() ?: ""
                if (!lastToken.contains(".")) {
                    _expression.value += if (lastToken.isEmpty()) "0." else "."
                }
            }
            is CalculatorAction.Clear -> {
                _expression.value = ""
                _result.value = "0"
            }
            is CalculatorAction.Delete -> {
                val current = _expression.value
                if (current.isNotEmpty()) {
                    _expression.value = current.dropLast(1)
                    evaluateLivePreview()
                }
            }
            is CalculatorAction.ToggleSign -> {
                val current = _expression.value
                if (current.isNotEmpty()) {
                    if (current.startsWith("-")) {
                        _expression.value = current.substring(1)
                    } else {
                        _expression.value = "-$current"
                    }
                    evaluateLivePreview()
                }
            }
            is CalculatorAction.Percentage -> {
                val current = _expression.value
                if (current.isNotEmpty()) {
                    try {
                        val eval = evaluateMath(current) / 100.0
                        _expression.value = formatter.format(eval)
                        _result.value = formatter.format(eval)
                    } catch (e: Exception) {
                        _result.value = "Error"
                    }
                }
            }
            is CalculatorAction.Calculate -> {
                val current = _expression.value
                if (current.isNotEmpty()) {
                    try {
                        val finalValue = evaluateMath(current)
                        val formatted = formatter.format(finalValue)
                        
                        val exprToSave = current
                        _expression.value = formatted
                        _result.value = formatted

                        viewModelScope.launch {
                            dao.insertHistory(HistoryItem(expression = exprToSave, result = formatted))
                        }
                    } catch (e: ArithmeticException) {
                        _result.value = e.message ?: "Math Error"
                    } catch (e: Exception) {
                        _result.value = "Format Error"
                    }
                }
            }
            is CalculatorAction.Memory -> {
                val currentVal = _result.value.toDoubleOrNull() ?: 0.0
                when (action.type) {
                    "MC" -> memoryValue = 0.0
                    "MR" -> {
                        _expression.value += formatter.format(memoryValue)
                        evaluateLivePreview()
                    }
                    "M+" -> memoryValue += currentVal
                    "M-" -> memoryValue -= currentVal
                }
            }
            is CalculatorAction.ToggleSciencePanel -> {
                _isScienceExpanded.value = !_isScienceExpanded.value
            }
        }
    }

    private fun evaluateLivePreview() {
        val current = _expression.value
        if (current.isEmpty()) {
            _result.value = "0"
            return
        }
        try {
            var sanitize = current
            if (sanitize.endsWith("+") || sanitize.endsWith("−") || sanitize.endsWith("×") || sanitize.endsWith("÷") || sanitize.endsWith("^")) {
                sanitize = sanitize.dropLast(1)
            }
            val openCount = sanitize.count { it == '(' }
            val closeCount = sanitize.count { it == ')' }
            if (openCount > closeCount) {
                sanitize += ")".repeat(openCount - closeCount)
            }

            val finalValue = evaluateMath(sanitize)
            _result.value = formatter.format(finalValue)
        } catch (_: Exception) {}
    }

    private fun evaluateMath(expr: String): Double {
        val parsed = expr
            .replace("sin(", "sin(")
            .replace("cos(", "cos(")
            .replace("tan(", "tan(")
            .replace("log(", "log(")
            .replace("ln(", "ln(")
            .replace("√", "sqrt")
            .replace("π", "π")
            .replace("e", "e")
        return MathParser(parsed).parse()
    }

    fun deleteHistoryItem(item: HistoryItem) {
        viewModelScope.launch {
            dao.deleteHistory(item)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            dao.clearAllHistory()
        }
    }

    fun toggleHaptic(enabled: Boolean) {
        _isHapticEnabled.value = enabled
    }

    fun toggleSettingsPanel(open: Boolean) {
        _isSettingsOpen.value = open
    }

    fun applyHistoryToExpression(item: HistoryItem) {
        _expression.value = item.expression
        _result.value = item.result
    }
}

class ViewModelFactory(private val dao: HistoryDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CalculatorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CalculatorViewModel(dao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel Class")
    }
}

// ==========================================
// 5. USER INTERFACE ARCHITECTURE LAYER
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val db = AppDatabase.getDatabase(applicationContext)
        val factory = ViewModelFactory(db.historyDao())

        setContent {
            val viewModel: CalculatorViewModel = viewModel(factory = factory)
            
            val bgCharcoal = Color(0xFF0D1117)
            val primaryGold = Color(0xFFD4A017)
            val secondaryChampagne = Color(0xFFF5E6C8)
            val accentEmerald = Color(0xFF00C896)
            val textWhite = Color(0xFFFFFFFF)
            val secondaryGray = Color(0xFFA1A1AA)

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = bgCharcoal
            ) {
                var showSplash by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    delay(1800)
                    showSplash = false
                }

                Crossfade(targetState = showSplash, animationSpec = tween(600)) { splashActive ->
                    if (splashActive) {
                        LuxurySplashScreen(primaryGold, bgCharcoal)
                    } else {
                        MainCalculatorLayout(
                            viewModel = viewModel,
                            bg = bgCharcoal,
                            primary = primaryGold,
                            secondary = secondaryChampagne,
                            accent = accentEmerald,
                            textCol = textWhite,
                            gray = secondaryGray
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LuxurySplashScreen(gold: Color, charcoal: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_glow")
    val scaleGlow by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scaleGlow"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(charcoal),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(150.dp)
                    .scale(scaleGlow)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                gold.copy(alpha = 0.35f),
                                Color.Transparent
                            )
                        )
                    )
            ) {
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .border(1.2.dp, gold, RoundedCornerShape(24.dp))
                        .background(charcoal, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "J2",
                        color = gold,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = FontFamily.Serif,
                        letterSpacing = 2.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "J H A  I I",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                letterSpacing = 8.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "PREMIUM COMPUTATION ENGINE",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = gold.copy(alpha = 0.8f),
                letterSpacing = 2.sp
            )
        }
    }
}

@Composable
fun MainCalculatorLayout(
    viewModel: CalculatorViewModel,
    bg: Color,
    primary: Color,
    secondary: Color,
    accent: Color,
    textCol: Color,
    gray: Color
) {
    val expr by 
 viewModel.expression.collectAsState()
    val res by viewModel.result.collectAsState()
    val isSciExpanded by viewModel.isScienceExpanded.collectAsState()
    val isSettingsOpen by viewModel.isSettingsOpen.collectAsState()
    val isHaptic by viewModel.isHapticEnabled.collectAsState()
    val historyList by viewModel.history.collectAsState(initial = emptyList())

    val context = LocalContext.current
    var isHistoryOpen by remember { mutableStateOf(false) }

    fun triggerVibration() {
        if (!isHaptic) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(18)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // Header Action Options Panel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "JHA2",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = primary,
                    letterSpacing = 2.sp,
                    fontFamily = FontFamily.Serif
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    IconButton(onClick = {
                        triggerVibration()
                        isHistoryOpen = !isHistoryOpen
                    }) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "History Log",
                            tint = if (isHistoryOpen) primary else textCol
                        )
                    }
                    IconButton(onClick = {
                        triggerVibration()
                        viewModel.toggleSettingsPanel(true)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "App Settings",
                            tint = textCol
                        )
                    }
                }
            }

            // Calculation Display Terminal
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.3f)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = expr.ifEmpty { " " },
                    style = MaterialTheme.typography.headlineSmall.copy(
                        color = gray,
                        textAlign = TextAlign.Right,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 1.sp
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                val adaptiveFontSize = when {
                    res.length > 15 -> 32.sp
                    res.length > 10 -> 44.sp
                    else -> 56.sp
                }
                
                Text(
                    text = res,
                    style = MaterialTheme.typography.displayLarge.copy(
                        color = textCol,
                        fontSize = adaptiveFontSize,
                        textAlign = TextAlign.Right,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
            }

            // Scientific Grid Configuration
            AnimatedVisibility(
                visible = isSciExpanded,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
            ) {
                ScientificFunctionGrid(
                    onAction = { action ->
                        triggerVibration()
                        viewModel.onAction(action)
                    },
                    accentColor = accent,
                    neutralColor = bg
                )
            }

            // Slide Toggle Indicator Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        triggerVibration()
                        viewModel.onAction(CalculatorAction.ToggleSciencePanel)
                    }
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSciExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = "Toggle Science Panel",
                    tint = primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(28.dp)
                )
            }

            // Master Classic/Science Input Keypad
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(2.6f)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                KeypadGrid(
                    onAction = { action ->
                        triggerVibration()
                        viewModel.onAction(action)
                    },
                    primaryGold = primary,
                    champagne = secondary,
                    charcoal = bg,
                    whiteText = textCol
                )
            }
        }

        // Animated Swipe History Panel Draw
        AnimatedVisibility(
            visible = isHistoryOpen,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            HistoryPanel(
                historyList = historyList,
                onApply = { item ->
                    triggerVibration()
                    viewModel.applyHistoryToExpression(item)
                    isHistoryOpen = false
                },
                onDelete = { item ->
                    triggerVibration()
                    viewModel.deleteHistoryItem(item)
                },
                onClearAll = {
                    triggerVibration()
                    viewModel.clearHistory()
                },
                onClose = {
                    triggerVibration()
                    isHistoryOpen = false
                },
                bg = bg,
                gold = primary,
                textCol = textCol,
                gray = gray
            )
        }

        // Settings Sheet Cover Modal
        AnimatedVisibility(
            visible = isSettingsOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            SettingsPanel(
                isHaptic = isHaptic,
                onHapticToggle = { viewModel.toggleHaptic(it) },
                onClose = { viewModel.toggleSettingsPanel(false) },
                bg = bg,
                gold = primary,
                textCol = textCol,
                gray = gray
            )
        }
    }
}
