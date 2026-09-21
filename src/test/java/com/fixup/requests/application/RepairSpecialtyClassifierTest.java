package com.fixup.requests.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixup.fixers.api.Specialty;
import org.junit.jupiter.api.Test;

class RepairSpecialtyClassifierTest {

    private final RepairSpecialtyClassifier classifier = new RepairSpecialtyClassifier();

    @Test
    void classifiesPlumbingWithAndWithoutAccents() {
        assertThat(classifier.classify("Tubería de agua", "Hay una fuga"))
                .isEqualTo(Specialty.PLUMBING);
        assertThat(classifier.classify("Tuberia de agua", "Hay una fuga"))
                .isEqualTo(Specialty.PLUMBING);
    }

    @Test
    void classifiesElectricalWithAccentsAndCase() {
        assertThat(classifier.classify("ELECTRICIDAD", "Apagón en la casa"))
                .isEqualTo(Specialty.ELECTRICAL);
    }

    @Test
    void classifiesFugaInodroPunctuation() {
        assertThat(classifier.classify("¡Fuga, en el lavamanos!", "Agua en el piso"))
                .isEqualTo(Specialty.PLUMBING);
    }

    @Test
    void classifiesCortoCircuito() {
        assertThat(classifier.classify("Problema eléctrico", "Hubo un corto circuito en el tablero"))
                .isEqualTo(Specialty.ELECTRICAL);
    }

    @Test
    void classifiesPainting() {
        assertThat(classifier.classify("Pintar brocha", "Necesito un pintor"))
                .isEqualTo(Specialty.PAINTING);
    }

    @Test
    void classifiesCarpentry() {
        assertThat(classifier.classify("Mueble roto", "Arreglar cajón y bisagras"))
                .isEqualTo(Specialty.CARPENTRY);
    }

    @Test
    void classifiesMasonry() {
        assertThat(classifier.classify("Grieta en muro", "Hay una fisura por mamposteria"))
                .isEqualTo(Specialty.MASONRY);
    }

    @Test
    void falseSubstringDoesNotMatch() {
        assertThat(classifier.classify("Los tubos", "Estan rotos"))
                .isEqualTo(Specialty.GENERAL);
    }

    @Test
    void ruleCountsOnlyOnce() {
        assertThat(classifier.classify("tuberia tuberia tuberia", "cable"))
                .isEqualTo(Specialty.GENERAL);

        assertThat(classifier.classify("tuberia tuberia tuberia", "nada mas"))
                .isEqualTo(Specialty.PLUMBING);
    }

    @Test
    void tieResolvesToGeneral() {
        assertThat(classifier.classify("Tubo y cable", "Fuga y cortocircuito"))
                .isEqualTo(Specialty.GENERAL);
    }

    @Test
    void noSignalResolvesToGeneral() {
        assertThat(classifier.classify("Ayuda", "Necesito arreglar algo"))
                .isEqualTo(Specialty.GENERAL);
    }

    @Test
    void classificationIsDeterministic() {
        String title = "Fuga de agua";
        String desc = "Se rompió la tubería en el baño";
        Specialty first = classifier.classify(title, desc);
        Specialty second = classifier.classify(title, desc);
        assertThat(first).isEqualTo(second).isEqualTo(Specialty.PLUMBING);
    }

    @Test
    void punctuationIsIgnored() {
        assertThat(classifier.classify("¡Fuga!", "¿Agua, inodoro?"))
                .isEqualTo(Specialty.PLUMBING);
    }
}