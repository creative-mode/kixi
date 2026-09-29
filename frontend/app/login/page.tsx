'use client';

import { useState } from 'react';
import { loginAction } from '@/app/actions/auth';
import { useRouter } from 'next/navigation';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { toast } from 'sonner';
import Image from 'next/image';

export default function LoginPage() {
  const [isLoading, setIsLoading] = useState(false);
  const router = useRouter();

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setIsLoading(true);

    const formData = new FormData(e.currentTarget);

    try {
      const result = await loginAction(formData);

      if (result.success) {
        toast.success('Login realizado com sucesso!', {
          description: 'A redirecionar para o painel...',
          duration: 3000,
        });

        await new Promise((resolve) => setTimeout(resolve, 400));

        router.push('/');
        router.refresh();
      } else {
        toast.error(result.error || 'Credenciais inválidas', {
          description: 'Verifique o username/email e a senha.',
        });
      }
    } catch (error) {
      console.error('Erro no login:', error);
      toast.error('Erro de conexão', {
        description: 'Não foi possível conectar ao servidor. Tente novamente.',
      });
    } finally {
      setIsLoading(false);
    }
  }

  return (
    <div className="min-h-screen flex">
      {/* Left — brand panel */}
      <div className="hidden lg:flex lg:w-1/2 bg-gray-900 relative items-center justify-center overflow-hidden">
        {/* Decorative circles */}
        <div className="absolute -top-24 -left-24 w-96 h-96 rounded-full bg-white/5" />
        <div className="absolute -bottom-32 -right-32 w-[500px] h-[500px] rounded-full bg-white/5" />
        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-72 h-72 rounded-full bg-white/5" />

        <div className="relative z-10 text-center px-12">
          <Image
            src="/manager/kixi-logo-white.svg"
            alt="Kixi"
            width={120}
            height={120}
            className="mx-auto mb-8 drop-shadow-lg"
            priority
          />
          <h1 className="text-white text-4xl font-bold tracking-tight mb-3">
            Kixi Manager
          </h1>
          <p className="text-white/80 text-lg max-w-sm mx-auto leading-relaxed">
            Painel de gestão administrativa do Banco de Enunciados.
          </p>
        </div>
      </div>

      {/* Right — login form */}
      <div className="flex-1 flex items-center justify-center px-6 py-12 bg-white">
        <div className="w-full max-w-sm">
          {/* Mobile logo */}
          <div className="lg:hidden flex justify-center mb-10">
            <Image
              src="/manager/kixi-logo.svg"
              alt="Kixi"
              width={64}
              height={64}
              priority
            />
          </div>

          <div className="mb-8">
            <h2 className="text-2xl font-bold text-gray-900 tracking-tight">
              Bem-vindo de volta
            </h2>
            <p className="text-gray-500 mt-1.5 text-sm">
              Inicie sessão para aceder ao painel de gestão.
            </p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-5">
            <div className="space-y-1.5">
              <Label htmlFor="usernameOrEmail" className="text-sm font-medium text-gray-700">
                Username ou Email
              </Label>
              <Input
                id="usernameOrEmail"
                name="usernameOrEmail"
                type="text"
                placeholder="admin@kixi.ao"
                required
                disabled={isLoading}
                autoComplete="username email"
                autoFocus
                className="h-11 bg-gray-50 border-gray-200 focus:bg-white focus:border-gray-900 focus:ring-gray-900/20 transition-all"
              />
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="password" className="text-sm font-medium text-gray-700">
                Palavra-passe
              </Label>
              <Input
                id="password"
                name="password"
                type="password"
                placeholder="••••••••"
                required
                disabled={isLoading}
                autoComplete="current-password"
                className="h-11 bg-gray-50 border-gray-200 focus:bg-white focus:border-gray-900 focus:ring-gray-900/20 transition-all"
              />
            </div>

            <Button
              type="submit"
              className="w-full h-11 bg-gray-900 hover:bg-gray-800 text-white font-medium shadow-sm transition-all duration-200"
              disabled={isLoading}
            >
              {isLoading ? (
                <div className="flex items-center gap-2">
                  <span className="animate-spin h-4 w-4 border-2 border-white border-t-transparent rounded-full" />
                  A entrar...
                </div>
              ) : (
                'Entrar'
              )}
            </Button>
          </form>

          <p className="mt-8 text-center text-xs text-gray-400">
            Problemas no acesso? Contacte o suporte técnico.
          </p>
        </div>
      </div>
    </div>
  );
}