package com.kukosin.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kukosin.app.data.Track
import com.kukosin.app.ui.theme.CreamMuted
import com.kukosin.app.ui.theme.Gold
import com.kukosin.app.ui.theme.Ink
import java.text.Normalizer

/**
 * Índice alfabético lateral de la lista de canciones.
 *
 * Arrastrás el dedo por la columna de letras y la lista salta a la primera canción
 * de esa letra. Mientras arrastrás, la letra activa aparece en un círculo dorado en
 * medio de la pantalla. Las letras salen sin tildes (Á → A), sólo se muestran las
 * que existen en la lista actual (respeta la búsqueda y el orden elegido) y '#' junta
 * números y símbolos.
 */

/** Primera letra "de orden": sin tildes, A–Z, y '#' para números y símbolos. */
internal fun firstLetterOf(text: String): Char {
    val first = text.trim().firstOrNull() ?: return '#'
    val normalized = Normalizer.normalize(first.uppercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    val letter = normalized.firstOrNull() ?: return '#'
    return if (letter in 'A'..'Z') letter else '#'
}

/** Letras presentes en la lista (en orden de aparición) y el índice de cada una. */
internal fun letterSections(
    tracks: List<Track>,
    keyOf: (Track) -> String = { it.title },
): Pair<List<Char>, Map<Char, Int>> {
    val order = ArrayList<Char>()
    val positions = HashMap<Char, Int>()
    tracks.forEachIndexed { index, track ->
        val letter = firstLetterOf(keyOf(track))
        if (!positions.containsKey(letter)) {
            positions[letter] = index
            order += letter
        }
    }
    return order to positions
}

@Composable
internal fun AlphabetIndexBar(
    letters: List<Char>,
    onSelect: (Char) -> Unit,
    onActiveChange: (Char?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (letters.isEmpty()) return
    var active by remember(letters) { mutableStateOf<Char?>(null) }

    Column(
        modifier = modifier
            .width(24.dp)
            .pointerInput(letters) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val barHeight = size.height.toFloat().coerceAtLeast(1f)

                    fun pick(y: Float) {
                        val position = ((y / barHeight) * letters.size).toInt().coerceIn(0, letters.lastIndex)
                        val letter = letters[position]
                        if (letter != active) {
                            active = letter
                            onActiveChange(letter)
                            onSelect(letter)
                        }
                    }

                    pick(down.position.y)
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        pick(change.position.y)
                        change.consume()
                    }

                    active = null
                    onActiveChange(null)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        letters.forEach { letter ->
            val isActive = letter == active
            Text(
                text = letter.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = if (isActive) 13.sp else 10.sp),
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                color = if (isActive) Gold else CreamMuted.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}

/** Círculo dorado con la letra activa, para no perder la referencia mientras arrastrás. */
@Composable
internal fun ActiveLetterBubble(letter: Char, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(68.dp)
            .background(Gold, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.toString(),
            color = Ink,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
