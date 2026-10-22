package com.fixup.visits.domain;

import java.util.List;

/**
 * FR-UC-27: una cocina cruza varias especialidades. La visita de valoración revisa cada frente por
 * separado para que la remodelación (FR-UC-01) se pueda dividir en necesidades concretas.
 */
final class KitchenChecklist {
    private static final List<String> LABELS = List.of(
            "Red de gas y gasodomésticos",
            "Tubería de agua, desagües y lavaplatos",
            "Tomas, iluminación y circuito eléctrico",
            "Gabinetes, puertas y herrajes",
            "Mesón y salpicadero",
            "Extracción y ventilación",
            "Pisos y enchapes");

    private KitchenChecklist() {
    }

    static List<Visit.ChecklistItem> items() {
        return java.util.stream.IntStream.range(0, LABELS.size())
                .mapToObj(index -> new Visit.ChecklistItem(index, LABELS.get(index), null, null))
                .toList();
    }
}
