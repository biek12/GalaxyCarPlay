let ticket = ''
export function setFactorTicket(value: string) { ticket = value }
export function takeFactorTicket() { const value = ticket; ticket = ''; return value }
