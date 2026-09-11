package com.elyndra.launcher.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.theme.LocalPoppins

/* ─────────────────────────────────────────────────────────────
   Un solo punto de entrada para el texto, para que ningún rótulo
   se escape de Poppins. Los tamaños del diseño son px CSS dentro
   de un lienzo de 412 de ancho, que es justo el ancho en dp de un
   móvil normal: por eso los números pasan a sp/dp tal cual.
   ───────────────────────────────────────────────────────────── */

@Composable
fun ElyText(
    text: String,
    modifier: Modifier = Modifier,
    size: Float = 12f,
    weight: FontWeight = FontWeight.Normal,
    color: Color = P.ink,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeightRatio: Float? = null,
    shadow: Shadow? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    uppercase: Boolean = false,
    align: TextAlign? = null,
) {
    Text(
        text = if (uppercase) text.uppercase() else text,
        modifier = modifier,
        fontFamily = LocalPoppins.current,
        fontSize = size.sp,
        fontWeight = weight,
        color = color,
        letterSpacing = letterSpacing,
        lineHeight = lineHeightRatio?.let { (size * it).sp } ?: TextUnit.Unspecified,
        textAlign = align,
        maxLines = maxLines,
        overflow = overflow,
        style = if (shadow != null) LocalTextStyle.current.copy(shadow = shadow) else LocalTextStyle.current,
    )
}

/** Espaciado de letras en `em`, igual que en el CSS. */
fun tracking(em: Float): TextUnit = em.em

/** Estilo base para los campos de entrada. */
@Composable
fun inputStyle(size: Float, color: Color = P.ink): TextStyle =
    LocalTextStyle.current.copy(
        fontFamily = LocalPoppins.current,
        fontSize = size.sp,
        color = color,
        fontWeight = FontWeight.Normal,
    )
