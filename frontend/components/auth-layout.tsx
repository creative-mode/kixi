'use client'

import { usePathname } from 'next/navigation'

const PUBLIC_PAGES = ['/login']

export function AuthLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname()
  const isPublicPage = PUBLIC_PAGES.includes(pathname)

  if (isPublicPage) {
    // No navbar padding, full-screen layout for login
    return (
      <main className="min-h-screen bg-background text-foreground">
        {children}
      </main>
    )
  }

  // Authenticated pages: add top padding for the fixed navbar
  return (
    <main className="pt-16 min-h-screen bg-background text-foreground">
      {children}
    </main>
  )
}
