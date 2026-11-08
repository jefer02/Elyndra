package com.elyndra.launcher.core.device

/**
 * Familias de chips conocidas, por `Build.SOC_MANUFACTURER` / `SOC_MODEL`
 * (Android 12+). Una lista corta de **familias** (patrones), no de modelos: así
 * se mantiene sola cuando sale un Helio o un Unisoc nuevo.
 *
 * Es deliberadamente asimétrica: la gama alta conocida protege a un equipo
 * potente de bajar de calidad aunque otra señal falle (RAM mal leída, cpufreq
 * cerrado), y la de entrada solo cubre familias que en ningún modelo dan para
 * desenfoques a pantalla completa a 60 fps. Lo que no está aquí no decide
 * nada ([Tier.Unknown]): siguen mandando RAM y CPU.
 *
 * Los nombres que devuelve el sistema varían ("MT6769Z", "mt6769", "T606",
 * "ums9230", "SM8650", "Tensor G3"…): se comparan en minúsculas y sin espacios.
 */
object SocCatalog {

    enum class Tier { Budget, HighEnd, Unknown }

    /** Fabricantes cuyos chips de este uso son todos de entrada (cajas de TV, Unisoc). */
    private val BUDGET_MAKERS = listOf("unisoc", "spreadtrum", "amlogic", "rockchip", "allwinner")

    /** Modelos de entrada por familia. */
    private val BUDGET_MODELS = listOf(
        // MediaTek Helio (A/P/G): MT67xx, y los MT65xx de antes.
        Regex("^mt6[57]\\d\\d"),
        // MediaTek de tabletas baratas (Kompanio 500 y anteriores).
        Regex("^mt81[0-8]\\d"),
        Regex("^helio"),
        // Unisoc (por si el fabricante viene vacío): T6xx/T7xx, SC98xx, UMS.
        Regex("^t[67]\\d\\d$"),
        Regex("^sc98\\d\\d"),
        Regex("^ums\\d"),
        // Snapdragon 4xx y 6xx bajos (460/480/662/665/680/685), 2xx y los MSM antiguos.
        Regex("^sdm4\\d\\d"),
        Regex("^sm4\\d\\d\\d"),
        Regex("^sm61\\d\\d"),
        Regex("^sm62\\d\\d"),
        Regex("^sdm6[0-3]\\d"),
        Regex("^msm89\\d\\d"),
        Regex("^qm215"),
        // Exynos de entrada (850, 7884/7904).
        Regex("^s5e3830"),
        Regex("^exynos(850|7884|7904)"),
    )

    /** Gama alta por familia: nunca baja de calidad por chip. */
    private val HIGH_END_MODELS = listOf(
        // Snapdragon 8 (Gen 1/2/3/Elite) y 8xx: SM8xxx.
        Regex("^sm8\\d\\d\\d"),
        // Dimensity 8000/9000: MT689x y MT69xx.
        Regex("^mt689\\d"),
        Regex("^mt69\\d\\d"),
        // Google Tensor.
        Regex("^tensor"),
        Regex("^gs\\d\\d\\d"),
        // Exynos 2100+ y 9xx de gama alta (s5e9xxx).
        Regex("^exynos2\\d\\d\\d"),
        Regex("^s5e9\\d\\d\\d"),
    )

    fun classify(manufacturer: String?, model: String?): Tier {
        val maker = normalize(manufacturer)
        val m = normalize(model)
        if (m.isEmpty() && maker.isEmpty()) return Tier.Unknown
        // La gama alta se mira primero: protege.
        if (m.isNotEmpty() && HIGH_END_MODELS.any { it.containsMatchIn(m) }) return Tier.HighEnd
        if (BUDGET_MAKERS.any { maker.startsWith(it) }) return Tier.Budget
        if (m.isNotEmpty() && BUDGET_MODELS.any { it.containsMatchIn(m) }) return Tier.Budget
        return Tier.Unknown
    }

    private fun normalize(s: String?): String =
        s?.lowercase()?.replace(" ", "")?.takeUnless { it == "unknown" || it == "null" }.orEmpty()
}
