package ir.challenge.balance.balance.application;

import ir.challenge.balance.shared.error.ErrorCode;

record OperationResult(ErrorCode error) {

    static OperationResult succeeded() {
        return new OperationResult(null);
    }

    static OperationResult rejected(ErrorCode error) {
        return new OperationResult(error);
    }
}
