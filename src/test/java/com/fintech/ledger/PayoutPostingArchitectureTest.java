
package com.fintech.ledger;

import com.fintech.ledger.port.LedgerProvider;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PayoutPostingArchitectureTest {

    private static final String LEGACY =
            PayoutSettlementService.class.getName();

    private static final Map<String, Set<String>> ALLOWED =
            Map.of(
                    "reservePayout",
                    Set.of(PayoutReservationService.class.getName()),

                    "settlePayout",
                    Set.of(
                            PayoutFinalizationExecutionService.class.getName(),
                            LEGACY
                    ),

                    "releasePayout",
                    Set.of(PayoutFinalizationExecutionService.class.getName())
            );

    private JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.fintech");
    }

    @Test
    void payoutLedgerPostingsHaveApprovedCallersOnly() {
        List<String> violations = new ArrayList<>();

        for (JavaClass caller : productionClasses()) {

            caller.getMethodCallsFromSelf().forEach(call ->
                    checkPosting(
                            caller,
                            call.getTarget().getOwner(),
                            call.getTarget().getName(),
                            violations
                    )
            );

            caller.getMethodReferencesFromSelf().forEach(reference ->
                    checkPosting(
                            caller,
                            reference.getTarget().getOwner(),
                            reference.getTarget().getName(),
                            violations
                    )
            );
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Unauthorized payout ledger calls:\n"
                        + String.join("\n", violations)
        );
    }

    private void checkPosting(
            JavaClass caller,
            JavaClass targetOwner,
            String methodName,
            List<String> violations
    ) {
        Set<String> permitted = ALLOWED.get(methodName);

        if (permitted == null) {
            return;
        }

        if (!targetOwner.isAssignableTo(LedgerProvider.class)) {
            return;
        }

        if (!permitted.contains(caller.getName())) {
            violations.add(
                    caller.getName() + " calls "
                            + targetOwner.getName() + "."
                            + methodName
            );
        }
    }

    @Test
    void productionCodeMustNotDependOnLegacySettlement() {
        List<String> violations = new ArrayList<>();

        for (JavaClass source : productionClasses()) {
            if (source.getName().equals(LEGACY)) {
                continue;
            }

            for (Dependency dependency :
                    source.getDirectDependenciesFromSelf()) {

                if (dependency.getTargetClass()
                        .getName().equals(LEGACY)) {

                    violations.add(dependency.getDescription());
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Production code references legacy settlement:\n"
                        + String.join("\n", violations)
        );
    }
}
