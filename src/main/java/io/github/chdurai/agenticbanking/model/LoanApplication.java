package io.github.chdurai.agenticbanking.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record LoanApplication(
        @NotBlank String applicantId,
        @NotBlank String fullName,
        @NotBlank String ssnLast4,
        @NotNull @DecimalMin("0") BigDecimal annualIncome,
        @Min(0) @Max(60) int employmentYears,
        @NotNull @DecimalMin("1000") BigDecimal requestedAmount,
        @NotBlank String loanPurpose,
        @Min(6) @Max(360) int termMonths,
        @NotNull @DecimalMin("0") BigDecimal existingMonthlyDebt,
        String employer,
        List<String> documents
) {
    public BigDecimal debtToIncomeRatio() {
        if (annualIncome.signum() == 0) {
            return BigDecimal.ONE;
        }
        BigDecimal monthlyIncome = annualIncome.divide(BigDecimal.valueOf(12), 4, java.math.RoundingMode.HALF_UP);
        return existingMonthlyDebt.divide(monthlyIncome, 4, java.math.RoundingMode.HALF_UP);
    }
}
