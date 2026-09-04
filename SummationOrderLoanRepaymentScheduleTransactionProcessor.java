/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.impl;

import org.apache.fineract.infrastructure.core.service.*;
import org.apache.fineract.organisation.monetary.domain.*;
import org.apache.fineract.portfolio.loanaccount.domain.*;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.*;
import org.apache.fineract.portfolio.loanaccount.serialization.*;
import org.apache.fineract.portfolio.loanaccount.service.*;
import org.springframework.stereotype.*;
import java.math.*;
import java.time.*;
import java.time.temporal.*;
import java.util.*;

@Component
public class SummationOrderLoanRepaymentScheduleTransactionProcessor extends AbstractLoanRepaymentScheduleTransactionProcessor {

    public static final String STRATEGY_CODE = "summation-order-strategy";
    public static final String STRATEGY_NAME = "all fees, all penalties, all interest, all principal";

    public SummationOrderLoanRepaymentScheduleTransactionProcessor(ExternalIdFactory externalIdFactory, LoanChargeValidator loanChargeValidator,
                                                                   LoanBalanceService loanBalanceService) {
        super(externalIdFactory, loanChargeValidator, loanBalanceService);
    }

    @Override
    public String getCode() {
        return STRATEGY_CODE;
    }

    @Override
    public String getName() {
        return STRATEGY_NAME;
    }

    @Override
    protected Money handleTransactionThatIsPaymentInAdvanceOfInstallment(LoanRepaymentScheduleInstallment currentInstallment, List<LoanRepaymentScheduleInstallment> installments, LoanTransaction loanTransaction, Money paymentInAdvance, List<LoanTransactionToRepaymentScheduleMapping> transactionMappings, Set<LoanCharge> charges) {

        final LocalDate transactionDate = loanTransaction.getTransactionDate();

        final MonetaryCurrency currency = paymentInAdvance.getCurrency();
        Money transactionAmountRemaining = paymentInAdvance;
        Money principalPortion = Money.zero(currency);
        Money interestPortion = Money.zero(currency);
        Money feeChargesPortion = Money.zero(currency);
        Money penaltyChargesPortion = Money.zero(currency);

        if (loanTransaction.isChargesWaiver()) {
            penaltyChargesPortion = currentInstallment.waivePenaltyChargesComponent(transactionDate,
                    loanTransaction.getPenaltyChargesPortion(currency));
            transactionAmountRemaining = transactionAmountRemaining.minus(penaltyChargesPortion);

            feeChargesPortion = currentInstallment.waiveFeeChargesComponent(transactionDate,
                    loanTransaction.getFeeChargesPortion(currency));
            transactionAmountRemaining = transactionAmountRemaining.minus(feeChargesPortion);

        } else if (loanTransaction.isInterestWaiver()) {
            interestPortion = currentInstallment.waiveInterestComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(interestPortion);

            loanTransaction.updateComponents(principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion);
        } else if (loanTransaction.isChargePayment()) {
            if (loanTransaction.isPenaltyPayment()) {
                penaltyChargesPortion = currentInstallment.payPenaltyChargesComponent(transactionDate, transactionAmountRemaining);
                transactionAmountRemaining = transactionAmountRemaining.minus(penaltyChargesPortion);
            } else {
                feeChargesPortion = currentInstallment.payFeeChargesComponent(transactionDate, transactionAmountRemaining);
                transactionAmountRemaining = transactionAmountRemaining.minus(feeChargesPortion);
            }
            loanTransaction.updateComponents(principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion);
        } else {
            boolean ignoreDueDateCheck = false;
            boolean rerun = false;

            do {
                Money subFeePortion;
                if (!ignoreDueDateCheck) {
                    subFeePortion = currentInstallment.payFeeChargesComponent(transactionDate, transactionAmountRemaining);
                } else {
                    subFeePortion = Money.zero(currency);
                }
                transactionAmountRemaining = transactionAmountRemaining.minus(subFeePortion);
                feeChargesPortion = feeChargesPortion.add(subFeePortion);

                Money subPenaltyPortion;
                if (!ignoreDueDateCheck) {
                    subPenaltyPortion = currentInstallment.payPenaltyChargesComponent(transactionDate, transactionAmountRemaining);
                } else {
                    subPenaltyPortion = Money.zero(currency);
                }
                transactionAmountRemaining = transactionAmountRemaining.minus(subPenaltyPortion);
                penaltyChargesPortion = penaltyChargesPortion.add(subPenaltyPortion);

                Money subInterestPortion;
                if (ignoreDueDateCheck || !transactionDate.isBefore(currentInstallment.getDueDate())) {
                    subInterestPortion = currentInstallment.payInterestComponent(transactionDate, transactionAmountRemaining);
                    transactionAmountRemaining = transactionAmountRemaining.minus(subInterestPortion);
                    interestPortion = interestPortion.add(subInterestPortion);
                }

                Money subPrincipalPortion = currentInstallment.payPrincipalComponent(transactionDate, transactionAmountRemaining);
                transactionAmountRemaining = transactionAmountRemaining.minus(subPrincipalPortion);
                principalPortion = principalPortion.add(subPrincipalPortion);
                // If the transactionAmountRemaining is greater than zero, rerun the allocation without due date check
                // to distribute the in advance portions
                if (transactionAmountRemaining.isGreaterThanZero()) {
                    ignoreDueDateCheck = true;
                }
                rerun = !rerun;
            } while (ignoreDueDateCheck && rerun);
            loanTransaction.updateComponents(principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion);
        }
        if (principalPortion.plus(interestPortion).plus(feeChargesPortion).plus(penaltyChargesPortion).isGreaterThanZero()) {
            transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, currentInstallment,
                    principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion));
        }
        return transactionAmountRemaining;
    }

    @Override
    protected Money handleTransactionThatIsOnTimePaymentOfInstallment(
            final LoanRepaymentScheduleInstallment currentInstallment,
            final LoanTransaction loanTransaction,
            final Money transactionAmountUnprocessed,
            List<LoanTransactionToRepaymentScheduleMapping> transactionMappings,
            Set<LoanCharge> charges) {

        final LocalDate transactionDate = loanTransaction.getTransactionDate();
        final MonetaryCurrency currency = transactionAmountUnprocessed.getCurrency();
        Money transactionAmountRemaining = transactionAmountUnprocessed;

        Money principalPortion = Money.zero(currency);
        Money interestPortion = Money.zero(currency);
        Money feeChargesPortion = Money.zero(currency);
        Money penaltyChargesPortion = Money.zero(currency);

        if (loanTransaction.isChargesWaiver()) {
            penaltyChargesPortion = currentInstallment.waivePenaltyChargesComponent(transactionDate,
                    loanTransaction.getPenaltyChargesPortion(currency));
            transactionAmountRemaining = transactionAmountRemaining.minus(penaltyChargesPortion);

            feeChargesPortion = currentInstallment.waiveFeeChargesComponent(transactionDate,
                    loanTransaction.getFeeChargesPortion(currency));
            transactionAmountRemaining = transactionAmountRemaining.minus(feeChargesPortion);

        } else if (loanTransaction.isInterestWaiver()) {
            interestPortion = currentInstallment.waiveInterestComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(interestPortion);

            loanTransaction.updateComponents(principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion);
        } else if (loanTransaction.isChargePayment()) {
            if (loanTransaction.isPenaltyPayment()) {
                penaltyChargesPortion = currentInstallment.payPenaltyChargesComponent(transactionDate, transactionAmountRemaining);
                transactionAmountRemaining = transactionAmountRemaining.minus(penaltyChargesPortion);
            } else {
                feeChargesPortion = currentInstallment.payFeeChargesComponent(transactionDate, transactionAmountRemaining);
                transactionAmountRemaining = transactionAmountRemaining.minus(feeChargesPortion);
            }
            loanTransaction.updateComponents(principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion);
        } else {
            // Summation order: fees, penalties, interest, principal for this installment
            Money subFeePortion = currentInstallment.payFeeChargesComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(subFeePortion);
            feeChargesPortion = feeChargesPortion.add(subFeePortion);

            Money subPenaltyPortion = currentInstallment.payPenaltyChargesComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(subPenaltyPortion);
            penaltyChargesPortion = penaltyChargesPortion.add(subPenaltyPortion);

            Money subInterestPortion = currentInstallment.payInterestComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(subInterestPortion);
            interestPortion = interestPortion.add(subInterestPortion);

            Money subPrincipalPortion = currentInstallment.payPrincipalComponent(transactionDate, transactionAmountRemaining);
            transactionAmountRemaining = transactionAmountRemaining.minus(subPrincipalPortion);
            principalPortion = principalPortion.add(subPrincipalPortion);

            loanTransaction.updateComponents(principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion);
        }
        if (principalPortion.plus(interestPortion).plus(feeChargesPortion).plus(penaltyChargesPortion).isGreaterThanZero()) {
            transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, currentInstallment,
                    principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion));
        }
        return transactionAmountRemaining;
    }

    @Override
    protected Money handleTransactionThatIsALateRepaymentOfInstallment(LoanRepaymentScheduleInstallment currentInstallment, List<LoanRepaymentScheduleInstallment> installments, LoanTransaction loanTransaction, Money transactionAmountUnprocessed, List<LoanTransactionToRepaymentScheduleMapping> transactionMappings, Set<LoanCharge> charges) {
        return handleTransactionThatIsOnTimePaymentOfInstallment(currentInstallment, loanTransaction, transactionAmountUnprocessed,
                transactionMappings, charges);
    }

    @Override
    protected Money processTransaction(final LoanTransaction loanTransaction, final MonetaryCurrency currency,
                                       final List<LoanRepaymentScheduleInstallment> installments, final Set<LoanCharge> charges, Money amountToProcess) {
        final LocalDate transactionDate = loanTransaction.getTransactionDate();
        Money transactionAmountUnprocessed = loanTransaction.getAmount(currency);
        if (amountToProcess != null) {
            transactionAmountUnprocessed = amountToProcess;
        }
        List<LoanTransactionToRepaymentScheduleMapping> transactionMappings = new ArrayList<>();

        // First pass: Calculate total arrears and track which installments are in arrears
        Money totalArrears = Money.zero(currency);
        for (LoanRepaymentScheduleInstallment installment : installments) {
            if (isInstallmentInArrears(installment, transactionDate)) {
                totalArrears = totalArrears
                        .plus(installment.getFeeChargesOutstanding(currency))
                        .plus(installment.getPenaltyChargesOutstanding(currency))
                        .plus(installment.getInterestOutstanding(currency))
                        .plus(installment.getPrincipalOutstanding(currency));
            }
        }

        // Second pass: Apply payment according to summation order rules
        Money amountRemaining = transactionAmountUnprocessed;

        // Phase 1: Pay arrears in true summation order (fees across all, then penalties across all, then interest across all, then principal across all)
        // 1. Pay all fees across arrears installments
        for (LoanRepaymentScheduleInstallment installment : installments) {
            if (isInstallmentInArrears(installment, transactionDate) && amountRemaining.isGreaterThanZero()) {
                Money feePaid = installment.payFeeChargesComponent(transactionDate, amountRemaining);
                amountRemaining = amountRemaining.minus(feePaid);

                if (feePaid.isGreaterThanZero()) {
                    transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(
                            loanTransaction, installment, Money.zero(currency), Money.zero(currency), feePaid, Money.zero(currency)
                    ));
                }
            }
        }

        // 2. Pay all penalties across arrears installments
        for (LoanRepaymentScheduleInstallment installment : installments) {
            if (isInstallmentInArrears(installment, transactionDate) && amountRemaining.isGreaterThanZero()) {
                Money penaltyPaid = installment.payPenaltyChargesComponent(transactionDate, amountRemaining);
                amountRemaining = amountRemaining.minus(penaltyPaid);

                if (penaltyPaid.isGreaterThanZero()) {
                    transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(
                            loanTransaction, installment, Money.zero(currency), Money.zero(currency), Money.zero(currency), penaltyPaid
                    ));
                }
            }
        }

        // 3. Pay all interest across arrears installments
        for (LoanRepaymentScheduleInstallment installment : installments) {
            if (isInstallmentInArrears(installment, transactionDate) && amountRemaining.isGreaterThanZero()) {
                Money interestPaid = installment.payInterestComponent(transactionDate, amountRemaining);
                amountRemaining = amountRemaining.minus(interestPaid);

                if (interestPaid.isGreaterThanZero()) {
                    transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(
                            loanTransaction, installment, Money.zero(currency), interestPaid, Money.zero(currency), Money.zero(currency)
                    ));
                }
            }
        }

        // 4. Pay all principal across arrears installments
        for (LoanRepaymentScheduleInstallment installment : installments) {
            if (isInstallmentInArrears(installment, transactionDate) && amountRemaining.isGreaterThanZero()) {
                Money principalPaid = installment.payPrincipalComponent(transactionDate, amountRemaining);
                amountRemaining = amountRemaining.minus(principalPaid);

                if (principalPaid.isGreaterThanZero()) {
                    transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(
                            loanTransaction, installment, principalPaid, Money.zero(currency), Money.zero(currency), Money.zero(currency)
                    ));
                }
            }
        }

        // Phase 2: Pay the first future installment in strategy order (fees, penalties, prorated interest,
        // principal). Interest is prorated to days elapsed since the installment's period start (fromDate)
        // so the unaccrued tail is neither owed nor waived — it simply has not accrued yet.
        //
        // Any surplus after that installment closes is cascaded as principal-only advance payments across
        // subsequent future installments. That reduces their outstanding principal and marks them as having
        // in-advance payments, which triggers the loan-level orchestration to run interest recalculation and
        // regenerate the remaining schedule with the reduced principal balance.
        boolean firstFutureInstallmentHandled = false;
        for (LoanRepaymentScheduleInstallment installment : installments) {
            if (!isFutureInstallment(installment, transactionDate) || !amountRemaining.isGreaterThanZero()) {
                continue;
            }

            if (!firstFutureInstallmentHandled) {
                Money proratedInterestDue = computeProratedInterest(installment, transactionDate, currency);
                Money currentInterestOutstanding = installment.getInterestOutstanding(currency);
                if (proratedInterestDue.isLessThan(currentInterestOutstanding)) {
                    Money reduction = currentInterestOutstanding.minus(proratedInterestDue);
                    BigDecimal newInterestCharged = installment.getInterestCharged(currency).minus(reduction).getAmount();
                    installment.updateInterestCharged(newInterestCharged);
                }

                Money feePortion = installment.payFeeChargesComponent(transactionDate, amountRemaining);
                amountRemaining = amountRemaining.minus(feePortion);

                Money penaltyPortion = amountRemaining.isGreaterThanZero()
                        ? installment.payPenaltyChargesComponent(transactionDate, amountRemaining)
                        : Money.zero(currency);
                amountRemaining = amountRemaining.minus(penaltyPortion);

                Money interestPortion = amountRemaining.isGreaterThanZero()
                        ? installment.payInterestComponent(transactionDate, amountRemaining)
                        : Money.zero(currency);
                amountRemaining = amountRemaining.minus(interestPortion);

                Money principalPortion = amountRemaining.isGreaterThanZero()
                        ? installment.payPrincipalComponent(transactionDate, amountRemaining)
                        : Money.zero(currency);
                amountRemaining = amountRemaining.minus(principalPortion);

                Money total = principalPortion.plus(interestPortion).plus(feePortion).plus(penaltyPortion);
                if (total.isGreaterThanZero()) {
                    transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, installment,
                            principalPortion, interestPortion, feePortion, penaltyPortion));
                }
                firstFutureInstallmentHandled = true;
            } else {
                Money principalPortion = installment.payPrincipalComponent(transactionDate, amountRemaining);
                amountRemaining = amountRemaining.minus(principalPortion);
                if (principalPortion.isGreaterThanZero()) {
                    transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, installment,
                            principalPortion, Money.zero(currency), Money.zero(currency), Money.zero(currency)));
                }
            }
        }

        // Calculate totals for transaction update
        Money totalPrincipalPaid = Money.zero(currency);
        Money totalInterestPaid = Money.zero(currency);
        Money totalFeePaid = Money.zero(currency);
        Money totalPenaltyPaid = Money.zero(currency);

        for (LoanTransactionToRepaymentScheduleMapping mapping : transactionMappings) {
            totalPrincipalPaid = totalPrincipalPaid.plus(mapping.getPrincipalPortion(currency));
            totalInterestPaid = totalInterestPaid.plus(mapping.getInterestPortion(currency));
            totalFeePaid = totalFeePaid.plus(mapping.getFeeChargesPortion(currency));
            totalPenaltyPaid = totalPenaltyPaid.plus(mapping.getPenaltyChargesPortion(currency));
        }

        loanTransaction.updateComponents(totalPrincipalPaid, totalInterestPaid, totalFeePaid, totalPenaltyPaid);
        loanTransaction.updateLoanTransactionToRepaymentScheduleMappings(transactionMappings);
        return amountRemaining;
    }

    private boolean isInstallmentInArrears(LoanRepaymentScheduleInstallment installment, LocalDate transactionDate) {
        return !transactionDate.isBefore(installment.getDueDate()) && installment.isNotFullyPaidOff();
    }

    private boolean isFutureInstallment(LoanRepaymentScheduleInstallment installment, LocalDate transactionDate) {
        return transactionDate.isBefore(installment.getDueDate());
    }

    /**
     * Prorates the installment's scheduled interest based on days elapsed since the installment's period start
     * (fromDate) up to the payment date, over the full days-in-period. Intermediate repayments do not shift
     * the anchor — the period start is always the schedule's own fromDate.
     */
    private Money computeProratedInterest(LoanRepaymentScheduleInstallment installment, LocalDate paymentDate,
                                         MonetaryCurrency currency) {
        LocalDate periodStart = installment.getFromDate();
        LocalDate periodEnd = installment.getDueDate();
        Money scheduledInterest = installment.getInterestCharged(currency);

        if (periodStart == null || periodEnd == null || !scheduledInterest.isGreaterThanZero()) {
            return scheduledInterest;
        }

        long daysInPeriod = ChronoUnit.DAYS.between(periodStart, periodEnd);
        long daysElapsed = ChronoUnit.DAYS.between(periodStart, paymentDate);
        if (daysInPeriod <= 0 || daysElapsed <= 0) {
            return Money.zero(currency);
        }
        if (daysElapsed >= daysInPeriod) {
            return scheduledInterest;
        }

        BigDecimal prorated = scheduledInterest.getAmount()
                .multiply(BigDecimal.valueOf(daysElapsed))
                .divide(BigDecimal.valueOf(daysInPeriod), MoneyHelper.getMathContext());
        return Money.of(currency, prorated);
    }

    @Override
    protected Money handleRefundTransactionPaymentOfInstallment(LoanRepaymentScheduleInstallment currentInstallment, LoanTransaction loanTransaction, Money transactionAmountUnprocessed, List<LoanTransactionToRepaymentScheduleMapping> transactionMappings) {
        final LocalDate transactionDate = loanTransaction.getTransactionDate();
        final MonetaryCurrency currency = transactionAmountUnprocessed.getCurrency();
        Money transactionAmountRemaining = transactionAmountUnprocessed;

        Money principalPortion = Money.zero(currency);
        Money interestPortion = Money.zero(currency);
        Money feeChargesPortion = Money.zero(currency);
        Money penaltyChargesPortion = Money.zero(currency);

        // Summation order: fees, penalties, interest, principal for this installment
        Money subFeePortion = currentInstallment.unpayFeeChargesComponent(transactionDate, transactionAmountRemaining);
        transactionAmountRemaining = transactionAmountRemaining.minus(subFeePortion);
        feeChargesPortion = feeChargesPortion.add(subFeePortion);

        Money subPenaltyPortion = currentInstallment.unpayPenaltyChargesComponent(transactionDate, transactionAmountRemaining);
        transactionAmountRemaining = transactionAmountRemaining.minus(subPenaltyPortion);
        penaltyChargesPortion = penaltyChargesPortion.add(subPenaltyPortion);

        Money subInterestPortion = currentInstallment.unpayInterestComponent(transactionDate, transactionAmountRemaining);
        transactionAmountRemaining = transactionAmountRemaining.minus(subInterestPortion);
        interestPortion = interestPortion.add(subInterestPortion);

        Money subPrincipalPortion = currentInstallment.unpayPrincipalComponent(transactionDate, transactionAmountRemaining);
        transactionAmountRemaining = transactionAmountRemaining.minus(subPrincipalPortion);
        principalPortion = principalPortion.add(subPrincipalPortion);

        loanTransaction.updateComponents(principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion);
        if (principalPortion.plus(interestPortion).plus(feeChargesPortion).plus(penaltyChargesPortion).isGreaterThanZero()) {
            transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, currentInstallment,
                    principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion));
        }
        return transactionAmountRemaining;
    }
}
