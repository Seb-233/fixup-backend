package com.fixup.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;


class LayerRulesTest {
    @Test
    void shouldRespectTechnicalLayerBoundaries() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.fixup");
        sharedMustNotDependOnBusinessModules.check(classes);
        webMustNotAccessPersistence.check(classes);
        apiMustNotExposePersistence.check(classes);
    }

    static final ArchRule sharedMustNotDependOnBusinessModules = noClasses().that()
            .resideInAPackage("com.fixup.shared..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.fixup.identityaccess..", "com.fixup.users..", "com.fixup.fixers..",
                    "com.fixup.properties..", "com.fixup.media..", "com.fixup.requests..",
                    "com.fixup.quotations..", "com.fixup.jobs..", "com.fixup.notifications..",
                    "com.fixup.messaging..", "com.fixup.payments..", "com.fixup.contracts..",
                    "com.fixup.analytics..");

    static final ArchRule webMustNotAccessPersistence = noClasses().that()
            .resideInAPackage("..web..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework.data..", "..infrastructure..")
            .allowEmptyShould(true);


    static final ArchRule apiMustNotExposePersistence = noClasses().that()
            .resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework.data..", "..infrastructure..")
            .allowEmptyShould(true);
}
