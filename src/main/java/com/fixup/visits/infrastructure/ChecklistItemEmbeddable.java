package com.fixup.visits.infrastructure;

import com.fixup.visits.api.ChecklistResult;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Embeddable
class ChecklistItemEmbeddable {
    @Column(name = "position", nullable = false)
    private int position;
    @Column(name = "label", nullable = false, length = 120)
    private String label;
    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 16)
    private ChecklistResult result;
    @Column(name = "note", length = 500)
    private String note;

    protected ChecklistItemEmbeddable() {
    }

    ChecklistItemEmbeddable(int position, String label, ChecklistResult result, String note) {
        this.position = position;
        this.label = label;
        this.result = result;
        this.note = note;
    }

    int position() {
        return position;
    }

    String label() {
        return label;
    }

    ChecklistResult result() {
        return result;
    }

    String note() {
        return note;
    }
}
