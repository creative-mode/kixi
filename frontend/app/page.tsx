'use client';

import { useEffect, useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Calendar, Plus, ArrowRight, GraduationCap, Shield } from 'lucide-react';
import Link from 'next/link';
import Image from 'next/image';
import { getActiveSchoolYears } from '@/app/actions/school-year';
import { fetchCurrentUser } from '@/lib/auth';
import type { SchoolYearResponse } from '@/types/school-year';

export default function ManagerDashboard() {
  const [schoolYears, setSchoolYears] = useState<SchoolYearResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [userRoles, setUserRoles] = useState<string[]>([]);

  useEffect(() => {
    async function loadData() {
      try {
        const [years, user] = await Promise.all([
          getActiveSchoolYears().catch(() => []),
          fetchCurrentUser(),
        ]);
        setSchoolYears(years);
        if (user) setUserRoles(user.roles);
      } catch {
        // Silently handle errors
      } finally {
        setLoading(false);
      }
    }
    loadData();
  }, []);

  return (
    <div className="p-6 md:p-8 lg:p-10 max-w-7xl mx-auto space-y-8">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-3xl font-bold text-gray-900 tracking-tight">Dashboard</h1>
          <p className="text-gray-500 mt-1">
            Bem-vindo ao painel de gestão do Kixi.
          </p>
        </div>
        <Image
          src="/manager/kixi-logo.svg"
          alt="Kixi"
          width={40}
          height={40}
          className="opacity-30 hidden md:block"
        />
      </div>

      {/* Stats cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
        <Card className="border-gray-100 shadow-sm hover:shadow-md transition-shadow">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-gray-500">Anos Letivos</CardTitle>
            <div className="h-9 w-9 rounded-lg bg-gray-100 flex items-center justify-center">
              <Calendar className="h-4 w-4 text-gray-700" />
            </div>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-gray-900">
              {loading ? '—' : schoolYears.length}
            </div>
            <p className="text-xs text-gray-400 mt-1">
              ativos no sistema
            </p>
          </CardContent>
        </Card>

        <Card className="border-gray-100 shadow-sm hover:shadow-md transition-shadow">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-gray-500">Seu Perfil</CardTitle>
            <div className="h-9 w-9 rounded-lg bg-gray-100 flex items-center justify-center">
              <Shield className="h-4 w-4 text-gray-700" />
            </div>
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold text-gray-900 capitalize">
              {userRoles[0]?.toLowerCase() || '—'}
            </div>
            <p className="text-xs text-gray-400 mt-1">
              {userRoles.length > 1 ? `+ ${userRoles.length - 1} outros roles` : 'role principal'}
            </p>
          </CardContent>
        </Card>

        <Card className="border-gray-100 shadow-sm hover:shadow-md transition-shadow">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-gray-500">Ações Rápidas</CardTitle>
            <div className="h-9 w-9 rounded-lg bg-gray-100 flex items-center justify-center">
              <GraduationCap className="h-4 w-4 text-gray-700" />
            </div>
          </CardHeader>
          <CardContent className="space-y-2">
            <Button asChild size="sm" className="w-full justify-start bg-gray-900 hover:bg-gray-800 text-white">
              <Link href="/school-year/new">
                <Plus size={14} className="mr-2 text-white" />
                Novo Ano Letivo
              </Link>
            </Button>
            <Button asChild variant="outline" size="sm" className="w-full justify-start border-gray-200 text-gray-700 hover:bg-gray-50">
              <Link href="/school-year">
                <ArrowRight size={14} className="mr-2" />
                Ver Anos Letivos
              </Link>
            </Button>
          </CardContent>
        </Card>
      </div>

      {/* Recent School Years */}
      <Card className="border-gray-100 shadow-sm">
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle className="text-lg font-semibold text-gray-900">Anos Letivos Recentes</CardTitle>
          <Button asChild variant="ghost" size="sm" className="text-gray-700 hover:text-gray-900 hover:bg-gray-50">
            <Link href="/school-year">
              Ver todos <ArrowRight size={14} className="ml-1" />
            </Link>
          </Button>
        </CardHeader>
        <CardContent>
          {loading ? (
            <div className="flex items-center justify-center py-12">
              <span className="animate-spin h-5 w-5 border-2 border-gray-900 border-t-transparent rounded-full" />
            </div>
          ) : schoolYears.length === 0 ? (
            <div className="text-center py-12">
              <div className="h-12 w-12 rounded-full bg-gray-50 flex items-center justify-center mx-auto mb-4">
                <Calendar size={24} className="text-gray-300" />
              </div>
              <p className="text-gray-500 mb-4">Nenhum ano letivo registado ainda.</p>
              <Button asChild className="bg-gray-900 hover:bg-gray-800 text-white">
                <Link href="/school-year/new">
                  <Plus size={16} className="mr-2" />
                  Criar Primeiro Ano Letivo
                </Link>
              </Button>
            </div>
          ) : (
            <div className="space-y-2">
              {schoolYears.slice(0, 5).map((year) => (
                <Link
                  key={year.id}
                  href={`/school-year/${year.id}`}
                  className="flex items-center justify-between p-4 rounded-xl border border-gray-100 hover:border-gray-300 hover:bg-gray-50/50 transition-all group"
                >
                  <div className="flex items-center gap-4">
                    <div className="h-10 w-10 rounded-lg bg-gray-100 flex items-center justify-center group-hover:bg-gray-200 transition-colors">
                      <Calendar size={18} className="text-gray-700" />
                    </div>
                    <div>
                      <h3 className="font-semibold text-gray-900">
                        {year.startYear} – {year.endYear}
                      </h3>
                      <p className="text-xs text-gray-400">
                        Ano letivo
                      </p>
                    </div>
                  </div>
                  <ArrowRight size={16} className="text-gray-300 group-hover:text-gray-900 transition-colors" />
                </Link>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
