package com.elyndra.launcher.ui.masha.lipsync

/**
 * Visemas de los labios (conjunto de 15 de Meta sin "sil"), en el orden de los
 * canales de las curvas. Por visema:
 * - [cap]: tope del peso (nada se queda en 1,0; vocales 0,55–0,7);
 * - [jaw]: cuánto abre la mandíbula (fracción de `jawMax`);
 * - [lipAlpha]/[thetaPre]/[thetaPost]: dominancia de Cohen–Massaro en el canal
 *   de los labios (α y caída en 1/s antes y después del segmento). Los de
 *   lengua (DD, NN, KK, RR del español) tienen α baja: los labios toman la forma
 *   de las vocales vecinas. O/U usan `LipSyncConfig.roundThetaPre` antes;
 * - [jawAlpha]: dominancia en el canal de la mandíbula.
 */
enum class Vis(val cap: Float, val jaw: Float, val lipAlpha: Float, val thetaPre: Float, val thetaPost: Float, val jawAlpha: Float) {
    AA(0.70f, 1.00f, 1.0f, 16f, 16f, 1.0f),
    E(0.60f, 0.55f, 1.0f, 16f, 16f, 1.0f),
    I(0.55f, 0.35f, 1.0f, 16f, 16f, 1.0f),
    O(0.65f, 0.60f, 1.2f, 7f, 12f, 1.0f),
    U(0.60f, 0.30f, 1.25f, 6.5f, 12f, 1.0f),
    PP(0.90f, 0.00f, 1.2f, 30f, 30f, 1.0f),
    FF(0.75f, 0.12f, 1.2f, 26f, 26f, 0.9f),
    TH(0.55f, 0.20f, 0.6f, 22f, 22f, 0.7f),
    DD(0.50f, 0.25f, 0.35f, 22f, 22f, 0.6f),
    KK(0.45f, 0.28f, 0.3f, 22f, 22f, 0.6f),
    CH(0.60f, 0.10f, 1.1f, 12f, 16f, 0.9f),
    SS(0.55f, 0.10f, 0.9f, 18f, 18f, 0.9f),
    NN(0.45f, 0.22f, 0.35f, 22f, 22f, 0.6f),
    RR(0.50f, 0.25f, 0.5f, 18f, 18f, 0.6f),
    ;

    val rounded: Boolean get() = this == O || this == U

    companion object {
        /** Nº de canales de labios. */
        val COUNT = entries.size
    }
}

/** Clase articulatoria: duración propia, coste de alineado y reglas duras. */
enum class Cls(val weight: Float) {
    VOWEL(1f), GLIDE(0.5f), STOP(0.7f), NASAL(0.75f), FRIC(0.9f), AFFR(0.9f), LIQUID(0.6f), TAP(0.35f), TRILL(1f), APPROX(0.5f),
}

/**
 * Fonemas (inventario común a todos los idiomas, cercano al AFI). Cada uno
 * lleva su visema principal ([vis], [amount]) y, a veces, un tinte de otro
 * ([vis2], [amount2]): ñ = NN + algo de I; la R inglesa, redondeada.
 * [closure] = cierre de labios obligatorio (p/b/m oclusivas); [labiodental] =
 * labio inferior a los dientes (f/v).
 */
enum class Ph(
    val vis: Vis,
    val cls: Cls,
    val amount: Float = 1f,
    val vis2: Vis? = null,
    val amount2: Float = 0f,
    val closure: Boolean = false,
    val labiodental: Boolean = false,
) {
    // Vocales
    A(Vis.AA, Cls.VOWEL), E(Vis.E, Cls.VOWEL), I(Vis.I, Cls.VOWEL), O(Vis.O, Cls.VOWEL), U(Vis.U, Cls.VOWEL),
    AE(Vis.AA, Cls.VOWEL, 0.85f, Vis.E, 0.35f),
    AH(Vis.AA, Cls.VOWEL, 0.6f),
    /** Schwa: poca forma y poca mandíbula. */
    AX(Vis.AA, Cls.VOWEL, 0.35f, Vis.E, 0.15f),
    AO(Vis.O, Cls.VOWEL, 0.9f),
    UH(Vis.U, Cls.VOWEL, 0.7f),
    IH(Vis.I, Cls.VOWEL, 0.8f),
    ER(Vis.RR, Cls.VOWEL, 0.7f, Vis.E, 0.3f),
    /** ü/y francesa y alemana: labios de U con lengua de I. */
    YV(Vis.U, Cls.VOWEL, 0.85f, Vis.I, 0.2f),
    /** ø/œ. */
    OE(Vis.O, Cls.VOWEL, 0.75f, Vis.E, 0.3f),

    // Semivocales
    JG(Vis.I, Cls.GLIDE, 0.6f),
    WG(Vis.U, Cls.GLIDE, 0.75f),

    // Oclusivas
    P(Vis.PP, Cls.STOP, closure = true), B(Vis.PP, Cls.STOP, closure = true),
    /** [β] del español ("la vaca"): labios casi juntos, sin cierre obligatorio. */
    BH(Vis.PP, Cls.APPROX, 0.5f),
    T(Vis.DD, Cls.STOP), D(Vis.DD, Cls.STOP),
    /** [ð] débil del español ("nada", "verdad"). */
    DHW(Vis.TH, Cls.APPROX, 0.35f),
    K(Vis.KK, Cls.STOP), G(Vis.KK, Cls.STOP),
    /** [ɣ] ("lago"). */
    GH(Vis.KK, Cls.APPROX, 0.5f),

    // Nasales
    M(Vis.PP, Cls.NASAL, closure = true), N(Vis.NN, Cls.NASAL),
    NY(Vis.NN, Cls.NASAL, 0.9f, Vis.I, 0.35f),
    NG(Vis.NN, Cls.NASAL, 0.6f, Vis.KK, 0.4f),

    // Fricativas
    F(Vis.FF, Cls.FRIC, labiodental = true), V(Vis.FF, Cls.FRIC, 0.9f, labiodental = true),
    TH(Vis.TH, Cls.FRIC), DH(Vis.TH, Cls.FRIC, 0.8f),
    S(Vis.SS, Cls.FRIC), Z(Vis.SS, Cls.FRIC, 0.9f),
    SH(Vis.CH, Cls.FRIC), ZH(Vis.CH, Cls.FRIC, 0.9f),
    /** Jota /x/ (y la ch alemana). */
    X(Vis.KK, Cls.FRIC, 0.9f),
    /** h inglesa/alemana: los labios los ponen las vecinas. */
    HH(Vis.KK, Cls.APPROX, 0.2f),

    // Africadas
    CH(Vis.CH, Cls.AFFR), JH(Vis.CH, Cls.AFFR, 0.95f),
    /** /ʝ/ de "yo", "llave" (yeísmo). */
    YC(Vis.CH, Cls.AFFR, 0.5f, Vis.I, 0.3f),

    // Líquidas y vibrantes
    L(Vis.NN, Cls.LIQUID, 0.8f),
    /** Vírgula española [ɾ]: sin redondeo (labios de nn con un toque de RR, contrato v2). */
    RT(Vis.NN, Cls.TAP, 0.6f, Vis.RR, 0.15f),
    /** Vibrante múltiple [r]: igual, algo más marcada. */
    RR(Vis.NN, Cls.TRILL, 0.7f, Vis.RR, 0.2f),
    /** R inglesa: algo redondeada. */
    REN(Vis.RR, Cls.LIQUID, 1f, Vis.U, 0.25f),
    /** R uvular (francés, alemán, portugués). */
    RUV(Vis.KK, Cls.APPROX, 0.5f),
    ;

    val vowel: Boolean get() = cls == Cls.VOWEL
}

/** Un fonema con su acento léxico. */
data class Phone(val ph: Ph, val stress: Boolean = false)
