'use server';

import { redirect } from 'next/navigation';
import { revalidatePath } from 'next/cache';
import { getAuthHeaders } from '@/lib/auth';
import { API_BASE } from '@/lib/constants';
import type { SchoolYearRequest, SchoolYearResponse, SchoolYearList } from '@/types/school-year';


const BASE = `${API_BASE}/school-years`;

export async function getActiveSchoolYears(): Promise<SchoolYearList> {
  try {
    const headers = await getAuthHeaders();

    const res = await fetch(BASE, {
      method: 'GET',
      headers,
      next: { tags: ['school-years-active'] }, // para revalidação
    });

    if (!res.ok) {
      if (res.status === 401) throw new Error('Sessão expirada');
      throw new Error(`Erro ${res.status}: ${await res.text()}`);
    }

    return await res.json();
  } catch (err: any) {
    console.error('[getActiveSchoolYears]', err);
    throw new Error(err.message || 'Não foi possível carregar os anos letivos');
  }
}

export async function getTrashedSchoolYears(): Promise<SchoolYearList> {
  try {
    const headers = await getAuthHeaders();

    const res = await fetch(`${BASE}/trash`, {
      headers,
      next: { tags: ['school-years-trashed'] },
    });

    if (!res.ok) throw new Error(`Erro ${res.status}`);
    return await res.json();
  } catch (err: any) {
    throw new Error('Não foi possível carregar os anos letivos eliminados');
  }
}

export async function createSchoolYear(data: SchoolYearRequest) {
  try {
    const headers = await getAuthHeaders();

    const res = await fetch(BASE, {
      method: 'POST',
      headers,
      body: JSON.stringify(data),
    });

    if (!res.ok) {
      const errorText = await res.text();
      throw new Error(errorText.includes('validation') ? 'Dados inválidos' : errorText);
    }

    const created = (await res.json()) as SchoolYearResponse;

    revalidatePath('/school-years');
    revalidatePath('/school-years/trash');

    return { success: true, data: created };
  } catch (err: any) {
    return { success: false, error: err.message || 'Erro ao criar ano letivo' };
  }
}

export async function updateSchoolYear(id: number, data: SchoolYearRequest) {
  try {
    const headers = await getAuthHeaders();

    const res = await fetch(`${BASE}/${id}`, {
      method: 'PUT',
      headers,
      body: JSON.stringify(data),
    });

    if (!res.ok) throw new Error(await res.text());

    const updated = (await res.json()) as SchoolYearResponse;

    revalidatePath('/school-years');
    revalidatePath(`/school-years/${id}`);

    return { success: true, data: updated };
  } catch (err: any) {
    return { success: false, error: err.message || 'Erro ao atualizar' };
  }
}

export async function softDeleteSchoolYear(id: number) {
  try {
    const headers = await getAuthHeaders();

    const res = await fetch(`${BASE}/${id}`, {
      method: 'DELETE',
      headers,
    });

    if (!res.ok) throw new Error('Não foi possível eliminar');

    revalidatePath('/school-years');
    revalidatePath('/school-years/trash');

    return { success: true };
  } catch (err: any) {
    return { success: false, error: err.message || 'Erro ao mover para o lixo' };
  }
}

export async function restoreSchoolYear(id: number) {
  try {
    const headers = await getAuthHeaders();

    const res = await fetch(`${BASE}/${id}/restore`, {
      method: 'POST',
      headers,
    });

    if (!res.ok) throw new Error('Não foi possível restaurar');

    revalidatePath('/school-years');
    revalidatePath('/school-years/trash');

    return { success: true };
  } catch (err: any) {
    return { success: false, error: err.message || 'Erro ao restaurar' };
  }
}

export async function purgeSchoolYear(id: number) {
  try {
    const headers = await getAuthHeaders();

    const res = await fetch(`${BASE}/${id}/purge`, {
      method: 'DELETE',
      headers,
    });

    if (!res.ok) throw new Error('Não foi possível eliminar permanentemente');

    revalidatePath('/school-years/trash');

    return { success: true };
  } catch (err: any) {
    return { success: false, error: err.message || 'Erro ao eliminar permanentemente' };
  }
}

export async function getSchoolYearById(id: number): Promise<SchoolYearResponse> {
  if (!Number.isInteger(id) || id <= 0) {
    throw new Error('ID de ano letivo inválido');
  }

  try {
    const headers = await getAuthHeaders();

    const res = await fetch(`${API_BASE}/school-years/${id}`, {
      method: 'GET',
      headers,
      cache: 'no-store',           // sempre fresco para dados protegidos
      next: { tags: [`school-year-${id}`] }, // permite revalidação seletiva
    });

    if (!res.ok) {
      let errorMessage = await res.text().catch(() => 'Erro desconhecido');

      if (res.status === 401) {
        throw new Error('Sessão expirada. Faça login novamente.');
      }
      if (res.status === 403) {
        throw new Error('Sem permissão para aceder a este ano letivo.');
      }
      if (res.status === 404) {
        throw new Error('Ano letivo não encontrado.');
      }

      throw new Error(`Erro ${res.status}: ${errorMessage}`);
    }

    const data = (await res.json()) as SchoolYearResponse;

    // Validação mínima de shape (opcional mas recomendado)
    if (!data.id || !data.startYear || !data.endYear) {
      throw new Error('Resposta do servidor em formato inválido');
    }

    return data;
  } catch (err: any) {
    console.error(`[getSchoolYearById] ID ${id}:`, err);
    
    // Transforma em erro amigável para o frontend
    throw new Error(
      err.message?.includes('Sessão expirada') 
        ? 'Sessão expirada. Por favor, faça login novamente.'
        : err.message || 'Não foi possível carregar os dados do ano letivo. Tente novamente.'
    );
  }
}