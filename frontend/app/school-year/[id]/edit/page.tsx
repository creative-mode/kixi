'use client';

import { SchoolYearForm } from '@/components/school-year/school-year-form';
import { getSchoolYearById } from '@/app/actions/school-year';
import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { ArrowLeft, Edit } from 'lucide-react';
import Link from 'next/link';

export default function EditSchoolYearPage() {
  const params = useParams();
  const id = Number(params.id);
  const [schoolYear, setSchoolYear] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (id) {
      getSchoolYearById(id)
        .then((data) => {
          setSchoolYear(data);
          setLoading(false);
        })
        .catch(() => setLoading(false));
    }
  }, [id]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <span className="animate-spin h-6 w-6 border-2 border-gray-900 border-t-transparent rounded-full" />
      </div>
    );
  }

  if (!schoolYear) {
    return (
      <div className="p-8 text-center text-gray-400">
        Ano letivo não encontrado.
      </div>
    );
  }

  return (
    <div className="p-4 md:p-8 max-w-4xl mx-auto">
      <Link
        href="/school-year"
        className="inline-flex items-center gap-2 text-sm text-gray-500 hover:text-gray-900 transition-colors mb-6"
      >
        <ArrowLeft size={16} /> Voltar para lista
      </Link>

      <div className="flex items-center gap-3 mb-8">
        <div className="p-2 rounded-lg bg-gray-100">
          <Edit size={20} className="text-gray-700" />
        </div>
        <div>
          <h1 className="text-2xl md:text-3xl font-bold text-gray-900">
            Editar Ano Letivo
          </h1>
          <p className="text-sm text-gray-500">{schoolYear.startYear} – {schoolYear.endYear}</p>
        </div>
      </div>

      <SchoolYearForm initialData={schoolYear} />
    </div>
  );
}