package com.fixup.requests.application;

import com.fixup.fixers.api.Specialty;
import java.text.Normalizer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RepairSpecialtyClassifier {

    private static final Map<Specialty, Set<String>> KEYWORDS = new EnumMap<>(Specialty.class);

    static {
        KEYWORDS.put(Specialty.PLUMBING, Set.of(
                "plomeria", "plomero", "tuberia", "tubo", "fuga", "agua", "lavamanos",
                "inodoro", "desague", "grifo", "llave", "gotera", "humedad", "calentador",
                "caneria", "cisterna", "sifon"
        ));
        KEYWORDS.put(Specialty.ELECTRICAL, Set.of(
                "electricidad", "electrico", "electricista", "cable", "cableado", "cortocircuito",
                "corto circuito", "enchufe", "tomacorriente", "interruptor", "luz", "luces",
                "lampara", "bombillo", "breaker", "tablero", "apagon", "voltaje"
        ));
        KEYWORDS.put(Specialty.PAINTING, Set.of(
                "pintura", "pintor", "pintar", "brocha", "rodillo", "esmalte", "acrilico",
                "mancha", "descascarado", "estuco", "resane", "resanar", "pared", "techo"
        ));
        KEYWORDS.put(Specialty.CARPENTRY, Set.of(
                "carpinteria", "carpintero", "madera", "mueble", "puerta", "ventana", "bisagra",
                "chapa", "cerradura", "gabinete", "closet", "armario", "estante", "repisa",
                "escritorio", "cajon", "bisagras", "marco"
        ));
        KEYWORDS.put(Specialty.MASONRY, Set.of(
                "albanileria", "albanil", "mamposteria", "cemento", "concreto", "ladrillo",
                "bloque", "muro", "pared", "piso", "baldosa", "ceramica", "enchape", "friso",
                "grieta", "fisura", "techo", "teja", "impermeabilizacion"
        ));
    }

    public Specialty classify(String title, String description) {
        String combinedText = normalize((title == null ? "" : title) + " " + (description == null ? "" : description));

        Map<Specialty, Integer> scores = new EnumMap<>(Specialty.class);
        for (Specialty s : Specialty.values()) {
            if (s != Specialty.GENERAL) {
                scores.put(s, 0);
            }
        }

        for (Map.Entry<Specialty, Set<String>> entry : KEYWORDS.entrySet()) {
            Specialty specialty = entry.getKey();
            for (String rule : entry.getValue()) {
                String regex = "(?<![a-z0-9])" + Pattern.quote(rule) + "(?![a-z0-9])";
                Pattern pattern = Pattern.compile(regex);
                Matcher matcher = pattern.matcher(combinedText);
                if (matcher.find()) {
                    scores.put(specialty, scores.get(specialty) + 1);
                }
            }
        }

        int maxScore = 0;
        Specialty bestSpecialty = Specialty.GENERAL;
        boolean tie = false;

        for (Map.Entry<Specialty, Integer> entry : scores.entrySet()) {
            int score = entry.getValue();
            if (score > maxScore) {
                maxScore = score;
                bestSpecialty = entry.getKey();
                tie = false;
            } else if (score == maxScore && maxScore > 0) {
                tie = true;
            }
        }

        if (maxScore == 0 || tie) {
            return Specialty.GENERAL;
        }

        return bestSpecialty;
    }

    private String normalize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.replaceAll("[^a-zA-Z0-9\\\\s]", " ");
        normalized = normalized.replaceAll("\\\\s+", " ").trim();
        return normalized.toLowerCase();
    }
}