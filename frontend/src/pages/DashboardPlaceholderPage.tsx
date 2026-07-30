interface DashboardPlaceholderPageProps {
  nombreCompleto: string
  rol: string
  onLogout: () => void
}

export function DashboardPlaceholderPage({
  nombreCompleto,
  rol,
  onLogout,
}: DashboardPlaceholderPageProps) {
  return (
    <main style={{ maxWidth: 480, margin: '4rem auto', fontFamily: 'sans-serif' }}>
      <h1>Quality Control Center</h1>
      <p>
        Autenticado como <strong>{nombreCompleto}</strong> ({rol})
      </p>
      <button type="button" onClick={onLogout}>
        Cerrar sesión
      </button>
    </main>
  )
}
