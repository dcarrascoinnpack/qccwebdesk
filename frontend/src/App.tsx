import { useState } from 'react'
import type { LoginResponse } from './api/authApi'
import { LoginPage } from './pages/LoginPage'
import { DashboardPlaceholderPage } from './pages/DashboardPlaceholderPage'

const SESSION_STORAGE_KEY = 'qcc.session'

interface Session {
  token: string
  nombreCompleto: string
  rol: string
}

function readStoredSession(): Session | null {
  const raw = localStorage.getItem(SESSION_STORAGE_KEY)
  return raw ? (JSON.parse(raw) as Session) : null
}

function App() {
  const [session, setSession] = useState<Session | null>(readStoredSession)

  function handleLoginSuccess(response: LoginResponse) {
    const newSession: Session = {
      token: response.token!,
      nombreCompleto: response.nombreCompleto!,
      rol: response.rol!,
    }

    localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(newSession))
    setSession(newSession)
  }

  function handleLogout() {
    localStorage.removeItem(SESSION_STORAGE_KEY)
    setSession(null)
  }

  if (!session) {
    return <LoginPage onLoginSuccess={handleLoginSuccess} />
  }

  return (
    <DashboardPlaceholderPage
      nombreCompleto={session.nombreCompleto}
      rol={session.rol}
      onLogout={handleLogout}
    />
  )
}

export default App
