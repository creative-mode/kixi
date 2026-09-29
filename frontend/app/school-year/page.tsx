'use client';

import { useEffect, useState } from 'react';
import { getActiveSchoolYears, softDeleteSchoolYear } from '@/app/actions/school-year';
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
import { Eye, Edit, Trash2, Plus, Calendar, Archive } from 'lucide-react';
import type { SchoolYearResponse } from '@/types/school-year';

export default function SchoolYearsManager() {
  const [schoolYears, setSchoolYears] = useState<SchoolYearResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [deletingId, setDeletingId] = useState<number | null>(null);

  useEffect(() => {
    loadSchoolYears();
  }, []);

  async function loadSchoolYears() {
    setLoading(true);
    try {
      const data = await getActiveSchoolYears();
      setSchoolYears(data);
    } catch (error: any) {
      console.error('Erro ao carregar anos letivos:', error);
      toast.error(error.message || 'Erro ao carregar anos letivos');
    } finally {
      setLoading(false);
    }
  }

  async function handleSoftDelete(year: SchoolYearResponse) {
    if (!confirm(`Tem certeza que deseja mover o ano letivo ${year.startYear}–${year.endYear} para a lixeira?`)) {
      return;
    }

    setDeletingId(year.id);
    try {
      const result = await softDeleteSchoolYear(year.id);
      if (result.success) {
        toast.success(`Ano letivo ${year.startYear}–${year.endYear} movido para a lixeira`);
        await loadSchoolYears();
      } else {
        toast.error(result.error || 'Erro ao eliminar');
      }
    } catch (error: any) {
      toast.error(error.message || 'Erro ao eliminar');
    } finally {
      setDeletingId(null);
    }
  }

  return (
    <div className="p-4 md:p-8 max-w-7xl mx-auto">
      {/* Header */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 mb-8">
        <div>
          <div className="flex items-center gap-3 mb-1">
            <div className="p-2 rounded-lg bg-gray-100">
              <Calendar size={20} className="text-gray-700" />
            </div>
            <h1 className="text-2xl md:text-3xl font-bold text-gray-900">Anos Letivos</h1>
          </div>
          <p className="text-sm text-gray-500 ml-12">Gestão de períodos letivos do sistema</p>
        </div>
        
        <div className="flex gap-3">
          <Button asChild variant="outline" className="border-gray-200 text-gray-600 hover:bg-gray-50 hover:text-gray-900">
            <Link href="/school-year/trash">
              <Archive size={16} className="mr-2" />
              Lixeira
            </Link>
          </Button>
          <Button asChild className="bg-gray-900 hover:bg-gray-800 text-white shadow-sm">
            <Link href="/school-year/new">
              <Plus size={16} className="mr-2" />
              Novo Ano Letivo
            </Link>
          </Button>
        </div>
      </div>

      {/* Table Card */}
      <Card className="border-gray-200 shadow-sm">
        <CardHeader className="border-b border-gray-100 bg-gray-50/50">
          <CardTitle className="text-base font-semibold text-gray-800">Anos Letivos Ativos</CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow className="border-gray-100 hover:bg-transparent">
                <TableHead className="text-gray-500 font-medium">Ano Letivo</TableHead>
                <TableHead className="text-gray-500 font-medium">Início</TableHead>
                <TableHead className="text-gray-500 font-medium">Fim</TableHead>
                <TableHead className="text-gray-500 font-medium">Criado em</TableHead>
                <TableHead className="text-gray-500 font-medium">Últ. atualização</TableHead>
                <TableHead className="text-right text-gray-500 font-medium">Ações</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading ? (
                <TableRow>
                  <TableCell colSpan={6} className="text-center py-16 text-gray-400">
                    <div className="flex flex-col items-center gap-2">
                      <span className="animate-spin h-6 w-6 border-2 border-gray-900 border-t-transparent rounded-full" />
                      <span className="text-sm">A carregar anos letivos...</span>
                    </div>
                  </TableCell>
                </TableRow>
              ) : schoolYears.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} className="text-center py-16 text-gray-400">
                    <div className="flex flex-col items-center gap-2">
                      <Calendar size={32} className="text-gray-300" />
                      <span className="text-sm">Nenhum ano letivo registado ainda.</span>
                      <Button asChild size="sm" className="mt-2 bg-gray-900 hover:bg-gray-800 text-white">
                        <Link href="/school-year/new">
                          <Plus size={14} className="mr-1" />
                          Criar primeiro ano letivo
                        </Link>
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ) : (
                schoolYears.map((year) => (
                  <TableRow key={year.id} className="hover:bg-gray-50/50 border-gray-100 transition-colors">
                    <TableCell className="font-semibold text-gray-900">
                      {year.startYear} – {year.endYear}
                    </TableCell>
                    <TableCell className="text-gray-600">{year.startYear}</TableCell>
                    <TableCell className="text-gray-600">{year.endYear}</TableCell>
                    <TableCell className="whitespace-nowrap text-gray-500 text-sm">
                      {format(new Date(year.createdAt), "dd 'de' MMM yyyy, HH:mm", { locale: pt })}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-gray-500 text-sm">
                      {format(new Date(year.updatedAt), "dd 'de' MMM yyyy, HH:mm", { locale: pt })}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="sm" className="text-gray-500 hover:text-gray-900 hover:bg-gray-100" asChild>
                          <Link href={`/school-year/${year.id}`}>
                            <Eye size={16} />
                          </Link>
                        </Button>

                        <Button variant="ghost" size="sm" className="text-gray-500 hover:text-gray-900 hover:bg-gray-100" asChild>
                          <Link href={`/school-year/${year.id}/edit`}>
                            <Edit size={16} />
                          </Link>
                        </Button>
                        
                        <Button 
                          variant="ghost" 
                          size="sm"
                          className="text-gray-400 hover:text-red-600 hover:bg-red-50"
                          disabled={deletingId === year.id}
                          onClick={() => handleSoftDelete(year)}
                        >
                          {deletingId === year.id ? (
                            <span className="animate-spin h-4 w-4 border-2 border-current border-t-transparent rounded-full" />
                          ) : (
                            <Trash2 size={16} />
                          )}
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