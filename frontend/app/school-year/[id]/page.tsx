'use client';

import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { getSchoolYearById } from '@/app/actions/school-year';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import Link from 'next/link';
import { Button } from '@/components/ui/button';
import { Edit, ArrowLeft, Calendar, Clock, RefreshCw } from 'lucide-react';
import { format } from 'date-fns';
import { pt } from 'date-fns/locale';

export default function SchoolYearDetailsPage() {
  const params = useParams();
  const id = Number(params.id);
  const [schoolYear, setSchoolYear] = useState<any | null>(null);
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
      <div className="text-center py-16 text-gray-400">
        <Calendar size={40} className="mx-auto mb-3 text-gray-300" />
        <p>Ano letivo não encontrado.</p>
        <Button asChild variant="outline" size="sm" className="mt-4">
          <Link href="/school-year">Voltar para lista</Link>
        </Button>
      </div>
    );
  }

  return (
    <div className="p-4 md:p-8 max-w-4xl mx-auto">
      <div className="flex items-center justify-between mb-8">
        <Link
          href="/school-year"
          className="inline-flex items-center gap-2 text-sm text-gray-500 hover:text-gray-900 transition-colors"
        >
          <ArrowLeft size={16} /> Voltar para lista
        </Link>

        <Link href={`/school-year/${schoolYear.id}/edit`}>
          <Button variant="outline" size="sm" className="border-gray-200 text-gray-600 hover:text-gray-900 hover:border-gray-300">
            <Edit size={16} className="mr-2" /> Editar
          </Button>
        </Link>
      </div>

      <Card className="border-gray-200 shadow-sm">
        <CardHeader className="border-b border-gray-100 bg-gray-50/50">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-gray-100">
              <Calendar size={20} className="text-gray-700" />
            </div>
            <CardTitle className="text-2xl text-gray-900">
              {schoolYear.startYear} – {schoolYear.endYear}
            </CardTitle>
          </div>
        </CardHeader>
        <CardContent className="pt-8">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
            <div className="p-4 rounded-lg bg-gray-50 border border-gray-200">
              <h3 className="text-xs font-medium text-gray-500 uppercase tracking-wider mb-1">Ano de início</h3>
              <p className="text-2xl font-bold text-gray-900">{schoolYear.startYear}</p>
            </div>
            <div className="p-4 rounded-lg bg-gray-50 border border-gray-200">
              <h3 className="text-xs font-medium text-gray-500 uppercase tracking-wider mb-1">Ano de fim</h3>
              <p className="text-2xl font-bold text-gray-900">{schoolYear.endYear}</p>
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-6 border-t border-gray-100 pt-8 mt-8">
            <div className="flex items-start gap-3">
              <Clock size={16} className="text-gray-400 mt-0.5" />
              <div>
                <h3 className="text-xs font-medium text-gray-500 uppercase tracking-wider">Criado em</h3>
                <p className="mt-1 text-sm text-gray-700">
                  {format(new Date(schoolYear.createdAt), "dd 'de' MMMM 'de' yyyy, HH:mm", {
                    locale: pt,
                  })}
                </p>
              </div>
            </div>
            <div className="flex items-start gap-3">
              <RefreshCw size={16} className="text-gray-400 mt-0.5" />
              <div>
                <h3 className="text-xs font-medium text-gray-500 uppercase tracking-wider">Última atualização</h3>
                <p className="mt-1 text-sm text-gray-700">
                  {format(new Date(schoolYear.updatedAt), "dd 'de' MMMM 'de' yyyy, HH:mm", {
                    locale: pt,
                  })}
                </p>
              </div>
            </div>
            {schoolYear.deletedAt && (
              <div className="flex items-start gap-3">
                <Calendar size={16} className="text-red-400 mt-0.5" />
                <div>
                  <h3 className="text-xs font-medium text-red-500 uppercase tracking-wider">Eliminado em</h3>
                  <p className="mt-1 text-sm text-red-600">
                    {format(new Date(schoolYear.deletedAt), "dd 'de' MMMM 'de' yyyy, HH:mm", {
                      locale: pt,
                    })}
                  </p>
                </div>
              </div>
            )}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}