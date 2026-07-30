const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8085'

export interface LoginResponse {
  success: boolean
  message: string
  userId: number | null
  codigoUsuario: string | null
  nombreCompleto: string | null
  rol: string | null
  token: string | null
}

export async function login(codigoUsuario: string, password: string): Promise<LoginResponse> {
  const response = await fetch(`${API_BASE_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ codigoUsuario, password }),
  })

  return (await response.json()) as LoginResponse
}
