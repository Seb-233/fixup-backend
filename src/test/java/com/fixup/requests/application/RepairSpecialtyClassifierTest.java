package com.fixup.requests.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixup.fixers.api.Specialty;
import org.junit.jupiter.api.Test;

class RepairSpecialtyClassifierTest {

    private final RepairSpecialtyClassifier classifier = new RepairSpecialtyClassifier();

    @Test
    void classifiesPlumbing() {
        assertThat(classifier.classify("Fuga de agua", "Hay una fuga en el lavamanos"))
                .isEqualTo(Specialty.PLUMBING);
    }

    @Test
    void classifiesElectricalWithAccentsAndCase() {
        assertThat(classifier.classify("CORTOCIRCUITO", "Apagón en la casa"))
                .isEqualTo(Specialty.ELECTRICAL);
    }

    @Test
    void classifiesPhrase() {
        assertThat(classifier.classify("Problema", "Hubo un corto circuito en el tablero"))
                .isEqualTo(Specialty.ELECTRICAL);
    }

    @Test
    void classifiesPainting() {
        assertThat(classifier.classify("Pintar pared", "Necesito un pintor"))
                .isEqualTo(Specialty.PAINTING);
    }

    @Test
    void classifiesCarpentry() {
        assertThat(classifier.classify("Mueble roto", "Arreglar cajón y bisagras"))
                .isEqualTo(Specialty.CARPENTRY);
    }

    @Test
    void classifiesMasonry() {
        assertThat(classifier.classify("Grieta en muro", "Hay una fisura en el techo"))
                .isEqualTo(Specialty.MASONRY);
    }

    @Test
    void tieResolvesToGeneral() {
        assertThat(classifier.classify("Tubo y cable", "Fuga y cortocircuito"))
                .isEqualTo(Specialty.GENERAL);
    }

    @Test
    void noMatchResolvesToGeneral() {
        assertThat(classifier.classify("Ayuda", "Necesito arreglar algo"))
                .isEqualTo(Specialty.GENERAL);
    }

    @Test
    void ruleCountsOnce() {
        // "tubo" and "agua" (2 for plumbing), vs "cable", "cable", "cable" (1 for electrical because "cable" counts once)
        assertThat(classifier.classify("Tubo de agua", "El cable el cable el cable"))
                .isEqualTo(Specialty.PLUMBING);
    }

    @Test
    void wholeWordMatching() {
        // "tuberia" vs "tubo". If we search "tubo" in "tubos", it shouldn't match.
        // Wait, "tubo" is plumbing. If text is "tubos", it shouldn't match if it uses boundaries.
        // Let's test with a word that isn't exactly the keyword.
        assertThat(classifier.classify("Los tubos", "Estan rotos"))
                .isEqualTo(Specialty.GENERAL);
    }

    @Test
    void punctuationIsIgnored() {
        assertThat(classifier.classify("¡Fuga!", "¿Agua, inodoro?"))
                .isEqualTo(Specialty.PLUMBING);
    }
}