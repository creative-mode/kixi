'use client'

import { TechifyUIProvider } from '@techify/ui'
import '@techify/ui/styles.css'
import type React from 'react'
import { useServiceWorker } from '@/hooks/use-service-worker'

export function Providers({ children }: { children: React.ReactNode }) {
    // Register service worker for PWA
    useServiceWorker()

    return (
        <TechifyUIProvider
            config={{
                loading: { mode: 'blur', showText: false },
                defaults: { size: 'md', variant: 'primary' },
                feedback: {
                    showIcon: true,
                    showMessage: true,
                    autoReset: true,
                    autoResetDelay: 2000,
                },
                input: {
                    borderRadius: '0.5rem',
                    borderWidth: '1px',
                },
            }}
        >
            {children}
        </TechifyUIProvider>
    )
}

