'use client'

import { useState, useEffect } from 'react'
import { User } from 'lucide-react'
import { fetchCurrentUser } from '@/lib/auth'
import type { CurrentUser } from '@/types/auth'

export function UserDisplay() {
    const [user, setUser] = useState<CurrentUser | null>(null)

    useEffect(() => {
        fetchCurrentUser().then(setUser)
    }, [])

    if (!user) return null

    const primaryRole = user.roles?.[0] ?? 'User'

    return (
        <div className="flex items-center gap-2 text-sm">
            <div className="h-8 w-8 rounded-full bg-gray-100 flex items-center justify-center">
                <User size={16} className="text-gray-700" />
            </div>
            <div className="hidden lg:block">
                <p className="font-medium leading-none text-gray-900">Admin</p>
                <p className="text-xs text-gray-500 capitalize">{primaryRole.toLowerCase()}</p>
            </div>
        </div>
    )
}
