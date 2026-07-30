import { useState } from 'react'
import { login, type LoginResponse } from '../api/authApi'

interface LoginPageProps {
  onLoginSuccess: (session: LoginResponse) => void
}

export function LoginPage({ onLoginSuccess }: LoginPageProps) {
  const [codigoUsuario, setCodigoUsuario] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    setLoading(true)

    try {
      const response = await login(codigoUsuario, password)

      if (!response.success) {
        setError(response.message)
        return
      }

      onLoginSuccess(response)
    } catch {
      setError('No se pudo contactar al servidor.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <main style={{ maxWidth: 320, margin: '4rem auto', fontFamily: 'sans-serif' }}>
      <h1>Quality Control Center</h1>
      <form onSubmit={handleSubmit}>
        <div style={{ marginBottom: '1rem' }}>
          <label htmlFor="codigoUsuario">Código de usuario</label>
          <input
            id="codigoUsuario"
            value={codigoUsuario}
            onChange={(event) => setCodigoUsuario(event.target.value)}
            style={{ width: '100%' }}
            autoComplete="username"
          />
        </div>
        <div style={{ marginBottom: '1rem' }}>
          <label htmlFor="password">Contraseña</label>
          <input
            id="password"
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            style={{ width: '100%' }}
            autoComplete="current-password"
          />
        </div>
        {error && <p style={{ color: 'red' }}>{error}</p>}
        <button type="submit" disabled={loading}>
          {loading ? 'Ingresando...' : 'Ingresar'}
        </button>
      </form>
    </main>
  )
}
