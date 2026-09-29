'use client';

import { useEffect, useState } from 'react';
import { getTrashedSchoolYears, restoreSchoolYear, purgeSchoolYear } from '@/app/actions/school-year';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { format } from 'date-fns';
import { pt } from 'date-fns/locale';
import { Button } from '@/components/ui/button';
import Link from 'next/link';
import { toast } from 'sonner';
import { ArrowLeft, RotateCcw, Trash2 } from 'lucide-react';
import type { SchoolYearResponse } from '@/types/school-year';

export default function SchoolYearTrashPage() {
  const [schoolYears, setSchoolYears] = useState<SchoolYearResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [actionId, setActionId] = useState<number | null>(null);

  useEffect(() => {
    loadTrashed();
  }, []);

  async function loadTrashed() {
    setLoading(true);
    try {
      const data = await getTrashedSchoolYears();
      setSchoolYears(data);
    } catch (error: any) {
      console.error('Erro ao carregar lixeira:', error);
      toast.error(error.message || 'Erro ao carregar lixeira');
    } finally {
      setLoading(false);
    }
  }

  async function handleRestore(year: SchoolYearResponse) {
    setActionId(year.id);
    try {
      const result = await restoreSchoolYear(year.id);
      if (result.success) {
        toast.success(`Ano letivo ${year.startYear}–${year.endYear} restaurado`);
        await loadTrashed();
      } else {
        toast.error(result.error || 'Erro ao restaurar');
      }
    } catch (error: any) {
      toast.error(error.message || 'Erro ao restaurar');
    } finally {
      setActionId(null);
    }
  }

  async function handlePurge(year: SchoolYearResponse) {
    if (!confirm(`Tem certeza que deseja ELIMINAR PERMANENTEMENTE o ano letivo ${year.startYear}–${year.endYear}? Esta ação não pode ser desfeita.`)) {
      return;
    }

    setActionId(year.id);
    try {
      const result = await purgeSchoolYear(year.id);
      if (result.success) {
        toast.success(`Ano letivo ${year.startYear}–${year.endYear} eliminado permanentemente`);
        await loadTrashed();
      } else {
        toast.error(result.error || 'Erro ao eliminar permanentemente');
      }
    } catch (error: any) {
      toast.error(error.message || 'Erro ao eliminar permanentemente');
    } finally {
      setActionId(null);
    }
  }

  return (
    <div className="p-4 md:p-8 max-w-7xl mx-auto">
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 mb-8">
        <div>
          <Link
            href="/school-year"
            className="inline-flex items-center gap-2 text-gray-500 hover:text-gray-900 transition-colors mb-3 text-sm"
          >
            <ArrowLeft size={14} /> Voltar para lista
          </Link>
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-red-50">
              <Trash2 size={20} className="text-red-500" />
            </div>
            <div>
              <h1 className="text-2xl md:text-3xl font-bold text-gray-900">Lixeira</h1>
              <p className="text-sm text-gray-500">Anos letivos eliminados</p>
            </div>
          </div>
        </div>
      </div>

      <Card className="border-gray-200 shadow-sm">
        <CardHeader className="border-b border-gray-100 bg-gray-50/50">
          <CardTitle className="text-base font-semibold text-gray-800">Anos Letivos Eliminados</CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow className="border-gray-100 hover:bg-transparent">
                <TableHead className="text-gray-500 font-medium">Ano Letivo</TableHead>
                <TableHead className="text-gray-500 font-medium">Início</TableHead>
                <TableHead className="text-gray-500 font-medium">Fim</TableHead>
                <TableHead className="text-gray-500 font-medium">Eliminado em</TableHead>
                <TableHead className="text-right text-gray-500 font-medium">Ações</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading ? (
                <TableRow>
                  <TableCell colSpan={5} className="text-center py-16 text-gray-400">
                    <div className="flex flex-col items-center gap-2">
                      <span className="animate-spin h-6 w-6 border-2 border-gray-300 border-t-transparent rounded-full" />
                      <span className="text-sm">A carregar...</span>
                    </div>
                  </TableCell>
                </TableRow>
              ) : schoolYears.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5} className="text-center py-16 text-gray-400">
                    <div className="flex flex-col items-center gap-2">
                      <Trash2 size={32} className="text-gray-300" />
                      <span className="text-sm">A lixeira está vazia.</span>
                    </div>
                  </TableCell>
                </TableRow>
              ) : (
                schoolYears.map((year) => (
                  <TableRow key={year.id} className="hover:bg-red-50/30 border-gray-100 transition-colors">
                    <TableCell className="font-semibold text-gray-900">
                      {year.startYear} – {year.endYear}
                    </TableCell>
                    <TableCell className="text-gray-600">{year.startYear}</TableCell>
                    <TableCell className="text-gray-600">{year.endYear}</TableCell>
                    <TableCell className="whitespace-nowrap text-gray-500 text-sm">
                      {year.deletedAt
                        ? format(new Date(year.deletedAt), "dd 'de' MMM yyyy, HH:mm", { locale: pt })
                        : '—'}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-gray-500 hover:text-gray-900 hover:bg-gray-100"
                          disabled={actionId === year.id}
                          onClick={() => handleRestore(year)}
                        >
                          <RotateCcw size={16} className="mr-1" />
                          Restaurar
                        </Button>

                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-gray-400 hover:text-red-600 hover:bg-red-50"
                          disabled={actionId === year.id}
                          onClick={() => handlePurge(year)}
                        >
                          <Trash2 size={16} className="mr-1" />
                          Eliminar
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}
