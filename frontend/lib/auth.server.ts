import { cookies } from 'next/headers';
import { jwtVerify } from 'jose';
import 'server-only';

export { API_BASE } from './constants';

function getJwtSecret() {
  const secret = process.env.JWT_SECRET || 'default-secret-change-in-production-min-256-bits';
  return new TextEncoder().encode(secret);
}

export async function getAuthHeaders() {
  const cookieStore = await cookies();
  const token = cookieStore.get('auth_token')?.value;

  if (!token) {
    throw new Error('Não autenticado');
  }

  return {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json',
    'Accept': 'application/json',
  };
}

/**
 * Obtém o utilizador atual decodificando o JWT do cookie auth_token.
 * Para uso em Server Components e Route Handlers.
 */
export async function getCurrentUser() {
  try {
    const cookieStore = await cookies();
    const token = cookieStore.get('auth_token')?.value;

    if (!token) return null;

    const { payload } = await jwtVerify(token, getJwtSecret());

    return {
      accountId: Number(payload.sub),
      roles: (payload.roles as string[]) || [],
    };
  } catch (err) {
    console.error('Erro ao obter perfil do usuário:', err);
    return null;
  }
}
