package com.alejandro.bank.notifications.transfers;

// A quién se le avisa de una transferencia (BANK-72)
public enum Recipient {
    // El dueño de la cuenta origen: "Enviaste"
    SENDER,
    // El dueño de la cuenta destino: "Recibiste"
    RECEIVER,
    // Las dos cuentas son del mismo cliente: un solo aviso, "Moviste" (no un "enviaste" y un "recibiste")
    SELF
}
