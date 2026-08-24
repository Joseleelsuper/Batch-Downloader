/** Formatea fechas de servidor con la convención visible de la interfaz española. */
export function formatDate(value: string): string {
  return new Intl.DateTimeFormat('es-ES', {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(value));
}
