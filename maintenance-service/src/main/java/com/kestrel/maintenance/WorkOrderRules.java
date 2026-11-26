package com.kestrel.maintenance;

import static com.kestrel.maintenance.ApiModels.*;

final class WorkOrderRules {
    private WorkOrderRules() {
    }

    static void requireStatusChange(WorkOrder current, WorkStatus next) {
        if (current.status() == next) {
            return;
        }

        boolean hasPart = current.partRequirement() != null;
        boolean reserved = current.reservationStatus() == ReservationStatus.RESERVED;
        boolean allowed = switch (current.status()) {
            case OPEN -> next == WorkStatus.CHECKING_INVENTORY && hasPart
                    || next == WorkStatus.READY_FOR_MAINTENANCE && !hasPart;
            case CHECKING_INVENTORY -> next == WorkStatus.WAITING_FOR_PARTS || next == WorkStatus.PENDING_RESERVATION;
            case WAITING_FOR_PARTS, PENDING_RESERVATION -> next == WorkStatus.CHECKING_INVENTORY;
            case READY_FOR_MAINTENANCE -> next == WorkStatus.CHECKING_INVENTORY && hasPart && !reserved
                    || next == WorkStatus.IN_PROGRESS && current.technicianId() != null && (!hasPart || reserved);
            case IN_PROGRESS -> next == WorkStatus.COMPLETED;
            case COMPLETED, CANCELLED -> false;
        };

        if (!allowed && !(next == WorkStatus.CANCELLED && !current.status().terminal() && !reserved)) {
            throw ApiProblem.conflict("That work-order status change is not allowed.");
        }
    }

    static void requireNewReservation(WorkOrder current) {
        if (current.partRequirement() == null || current.reservationStatus() == ReservationStatus.RESERVED
                || (current.status() != WorkStatus.CHECKING_INVENTORY && current.status() != WorkStatus.PENDING_RESERVATION)) {
            throw ApiProblem.conflict("The order is not ready to record a new reservation.");
        }
    }
}
