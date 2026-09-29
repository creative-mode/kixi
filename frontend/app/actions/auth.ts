'use server';

import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';

interface LoginResponse {
  accessToken: string;
  tokenType: string;     // "Bearer"
  expiresAt: string;     // ISO instant (ex: "2026-02-17T15:30:00Z")
  accountId: number;
  roles: string[];
}

export async function loginAction(formData: FormData) {
  const usernameOrEmail = formData.get('usernameOrEmail') as string;
  const password = formData.get('password') as string;

  if (!usernameOrEmail || !password) {
    return { error: 'Username ou email e password são obrigatórios' };
  }

  try {
    const backendUrl =
      process.env.BACKEND_AUTH_URL ?? 'http://localhost:8080/api/v1/auth/login';

    const response = await fetch(backendUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ usernameOrEmail, password }),
      // Timeout para evitar ficar pendurado
      signal: AbortSignal.timeout(10000), // 10 segundos
    });

    if (!response.ok) {
      // Tenta ler mensagem de erro do backend se existir
      const errorData = await response.json().catch(() => ({}));
      return {
        error:
          errorData.message ||
          errorData.error ||
          `Erro ${response.status}: Falha na autenticação`,
      };
    }

    const data = (await response.json()) as LoginResponse;

    if (!data.accessToken || !data.tokenType?.toLowerCase().includes('bearer')) {
      return { error: 'Resposta inválida do servidor (token não recebido)' };
    }

    // Verifica se o utilizador tem role ADMIN — só admins podem aceder ao Kixi Manager
    if (!data.roles || !data.roles.includes('ADMIN')) {
      return { error: 'Acesso negado. Apenas administradores podem aceder ao Kixi Manager.' };
    }

    const cookieStore = await cookies();

    // Calcula maxAge aproximado em segundos a partir de expiresAt
    let maxAgeSeconds: number | undefined;
    try {
      const expires = new Date(data.expiresAt);
      const now = new Date();
      const diffMs = expires.getTime() - now.getTime();
      if (diffMs > 0) {
        maxAgeSeconds = Math.floor(diffMs / 1000);
      }
    } catch (e) {
      console.warn('Não foi possível parsear expiresAt:', data.expiresAt);
      // fallback: 24h se não conseguir calcular
      maxAgeSeconds = 60 * 60 * 24;
    }

    cookieStore.set('auth_token', data.accessToken, {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'lax',
      path: '/',
      maxAge: maxAgeSeconds,           // ideal: usa o tempo real de expiração
      // expires: new Date(data.expiresAt)  ← alternativa (mais precisa em alguns casos)
    });

    // Opcional: guardar informação mínima não-sensível (útil para UI rápida)
    // Não guarda password nem token aqui
    cookieStore.set(
      'user_info',
      JSON.stringify({
        accountId: data.accountId,
        roles: data.roles,
        // name / email / etc... só se o backend retornar
      }),
      {
        httpOnly: false,           // ← permite ler no client se quiseres
        secure: process.env.NODE_ENV === 'production',
        sameSite: 'lax',
        path: '/',
        maxAge: maxAgeSeconds,
      }
    );

    return { success: true };
  } catch (err: any) {
    console.error('Erro durante login:', err);
    return {
      error:
        err.name === 'TimeoutError'
          ? 'O servidor demorou muito a responder. Tente novamente.'
          : 'Erro ao conectar com o servidor de autenticação',
    };
  }
}

export async function logoutAction() {
  const cookieStore = await cookies();
  cookieStore.delete('auth_token');
  cookieStore.delete('user_info'); // se estiver usando
  redirect('/login');
}