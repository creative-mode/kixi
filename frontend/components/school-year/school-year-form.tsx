'use client';

import { FormBuilder, useInlineReaction } from '@techify/ui';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { createSchoolYear, updateSchoolYear } from '@/app/actions/school-year';
import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import type { FormField } from '@techify/ui';
import { fetchCurrentUser } from '@/lib/auth';
import { toast } from 'sonner';
import type { SchoolYearResponse } from '@/types/school-year';

const SchoolYearSchema = z.object({
  startYear: z
    .coerce.number({ invalid_type_error: 'Introduza um ano válido' })
    .positive('Ano de início deve ser positivo')
    .int('Deve ser um ano inteiro')
    .min(1900, 'Ano muito antigo')
    .max(new Date().getFullYear() + 10, 'Ano não pode ser muito futuro'),
  endYear: z
    .coerce.number({ invalid_type_error: 'Introduza um ano válido' })
    .positive('Ano de fim deve ser positivo')
    .int('Deve ser um ano inteiro')
    .min(1901, 'Ano muito antigo'),
}).refine((data) => data.endYear > data.startYear, {
  message: 'O ano de fim deve ser maior que o ano de início',
  path: ['endYear'],
});

type SchoolYearFormData = z.infer<typeof SchoolYearSchema>;

interface SchoolYearFormProps {
  initialData?: SchoolYearResponse;
}

export function SchoolYearForm({ initialData }: SchoolYearFormProps) {
  const router = useRouter();
  const { status, setLoading, setSuccess, setError, reset } = useInlineReaction();
  const [isReady, setIsReady] = useState(false);

  const form = useForm<SchoolYearFormData>({
    resolver: zodResolver(SchoolYearSchema),
    mode: 'onBlur',
    defaultValues: {
      startYear: initialData?.startYear || new Date().getFullYear(),
      endYear: initialData?.endYear || new Date().getFullYear() + 1,
    },
  });

  useEffect(() => {
    const checkAuth = async () => {
      try {
        const user = await fetchCurrentUser();
        if (user) {
          setIsReady(true);
        } else {
          toast.error('Você precisa estar logado para realizar esta ação.');
          router.push('/login');
        }
      } catch (error) {
        console.error('Auth check failed', error);
        toast.error('Erro ao verificar autenticação.');
      }
    };
    checkAuth();
  }, [router]);

  const fields: FormField<SchoolYearFormData>[] = [
    {
      name: 'startYear',
      type: 'number',
      label: 'Ano de Início',
      placeholder: 'Ex: 2025',
      required: true,
      successMessage: 'Ano válido ✓',
      colSpan: 'full',
    },
    {
      name: 'endYear',
      type: 'number',
      label: 'Ano de Fim',
      placeholder: 'Ex: 2026',
      required: true,
      successMessage: 'Ano válido ✓',
      colSpan: 'full',
    },
  ];

  const onSubmit = async (data: SchoolYearFormData) => {
    setLoading();

    const schoolYearData = {
      startYear: data.startYear,
      endYear: data.endYear,
    };

    let result;
    if (initialData) {
      result = await updateSchoolYear(initialData.id, schoolYearData);
    } else {
      result = await createSchoolYear(schoolYearData);
    }

    if (result.success) {
      setSuccess();
      toast.success(initialData ? 'Ano letivo atualizado!' : 'Ano letivo adicionado!');
      setTimeout(() => {
        router.push('/school-year');
        router.refresh(); // força atualização dos dados na lista
      }, 1000);
    } else {
      setError();
      toast.error(result.error || 'Erro ao salvar ano letivo.');
    }
  };

  if (!isReady) {
    return (
      <div className="flex items-center justify-center py-12">
        <span className="animate-spin h-6 w-6 border-2 border-gray-900 border-t-transparent rounded-full" />
      </div>
    );
  }

  return (
    <div className="bg-white border border-gray-200 rounded-xl p-8 max-w-2xl mx-auto shadow-sm">
      <div className="mb-8">
        <h2 className="text-xl font-semibold text-gray-900">
          {initialData ? 'Editar Ano Letivo' : 'Adicionar Novo Ano Letivo'}
        </h2>
        <p className="text-sm text-gray-500 mt-1">
          {initialData
            ? `Editando: ${initialData.startYear} – ${initialData.endYear}`
            : 'Preencha os anos de início e fim do período letivo'}
        </p>
      </div>

      <FormBuilder
        form={form}
        fields={fields}
        onSubmit={onSubmit}
        status={status}
        onReset={reset}
        columns={1}
        gap="md"
        submitText={initialData ? 'Atualizar Ano Letivo' : 'Adicionar Ano Letivo'}
        loadingText={initialData ? 'Atualizando...' : 'Adicionando...'}
        successMessage={initialData ? 'Ano letivo atualizado!' : 'Ano letivo adicionado!'}
        errorMessage="Erro ao salvar ano letivo."
        validationMode="inline-reaction"
        validateDebounce={300}
        inputBorderRadius="md"
      />
    </div>
  );
}