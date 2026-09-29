# Authentication System Documentation

## Overview

The Kixi Manager application now uses a secure, httpOnly cookie-based authentication system with user caching and middleware protection.

## Key Features

### 1. **HttpOnly Cookies** ✅
- Session tokens are stored in httpOnly cookies, preventing XSS attacks
- Cookies are set with `secure` flag in production
- `sameSite: 'strict'` prevents CSRF attacks
- 24-hour expiration time

### 2. **External Authentication Integration** ✅
- Primary authentication through `auth.kixi.ao`
- Validates admin credentials against external API
- Syncs user data with local PostgreSQL database
- Uses bcrypt for password hashing

### 3. **JWT Token Management** ✅
- Uses `jose` library for JWT creation and verification
- Tokens include `userId` and `role` in payload
- HS256 algorithm for signing
- Secret key from environment variables

### 4. **Request-Level Caching** ✅
- `getCurrentUser()` function uses React's `cache()` API
- User data is fetched once per request and cached
- Significantly improves performance for multiple user checks

### 5. **Middleware Protection** ✅
- Automatic route protection for authenticated areas
- Redirects unauthenticated users to login
- Role-based access control (RBAC) for admin routes
- Adds user info to request headers for fast access

## File Structure

```
manager/
├── middleware.ts                 # Route protection and JWT verification
├── lib/
│   └── auth.ts                   # User session utilities with caching
├── app/
│   ├── actions/
│   │   └── auth.ts              # Server actions for login/logout
│   └── login/
│       └── page.tsx             # Login page component
└── components/
    ├── providers.tsx            # Client-side UI provider wrapper
    └── user-display.tsx         # User info display component
```

## Usage Examples

### Server Components (Recommended)

```tsx
import { getCurrentUser, requireAuth, requireAdmin } from '@/lib/auth'

// Get current user (returns null if not authenticated)
export default async function MyPage() {
  const user = await getCurrentUser()
  
  if (!user) {
    return <div>Please log in</div>
  }
  
  return <div>Welcome, {user.name}!</div>
}

// Require authentication (throws error if not authenticated)
export default async function ProtectedPage() {
  const user = await requireAuth()
  return <div>Hello {user.name}</div>
}

// Require admin access
export default async function AdminPage() {
  const user = await requireAdmin()
  return <div>Admin: {user.name}</div>
}
```

### Client Components

```tsx
'use client'

import { getCurrentUser } from '@/lib/auth'
import { useEffect, useState } from 'react'

export function MyComponent() {
  const [user, setUser] = useState(null)
  
  useEffect(() => {
    getCurrentUser().then(setUser)
  }, [])
  
  return <div>{user?.name}</div>
}
```

### Server Actions

```tsx
'use server'

import { requireAuth } from '@/lib/auth'

export async function createPost(formData: FormData) {
  const user = await requireAuth()
  
  // Use user.id for authorId
  const post = await prisma.post.create({
    data: {
      title: formData.get('title'),
      authorId: String(user.id),
      // ...
    }
  })
  
  return { success: true, post }
}
```

## Authentication Flow

### Login Process

1. User submits credentials on `/login` page
2. `loginAction()` validates credentials with `auth.kixi.ao`
3. External API confirms user is ADMIN
4. User data is synced to local PostgreSQL database (upsert)
5. JWT token is generated with user ID and role
6. Token is stored in httpOnly cookie
7. User is redirected to `/manager/posts`

### Request Authentication

1. User makes request to protected route
2. Middleware intercepts request
3. JWT token is verified from cookie
4. User info is added to request headers
5. Request proceeds to route handler
6. Route can use `getCurrentUser()` to get cached user data

### Logout Process

1. User clicks logout button
2. `logoutAction()` deletes session cookie
3. User is redirected to home page

## Security Features

- ✅ **HttpOnly cookies** - JavaScript cannot access tokens
- ✅ **Secure flag** - HTTPS only in production
- ✅ **SameSite strict** - CSRF protection
- ✅ **JWT verification** - All requests validated
- ✅ **Role-based access** - Admin-only routes protected
- ✅ **Password hashing** - bcrypt with salt rounds
- ✅ **External auth** - Centralized authentication service
- ✅ **Token expiration** - 24-hour validity

## Environment Variables

Required in `.env`:

```env
JWT_SECRET=your-secret-key-here
DATABASE_URL=postgresql://...
```

## API Reference

### `getCurrentUser()`
Returns the current authenticated user with caching.

**Returns:** `Promise<CurrentUser | null>`

**Example:**
```tsx
const user = await getCurrentUser()
if (user) {
  console.log(user.id, user.email, user.role)
}
```

### `getCurrentUserId()`
Fast access to user ID from request headers.

**Returns:** `Promise<number | null>`

### `getCurrentUserRole()`
Fast access to user role from request headers.

**Returns:** `Promise<string | null>`

### `isAdmin()`
Check if current user is an admin.

**Returns:** `Promise<boolean>`

### `requireAuth()`
Require authentication, throws error if not logged in.

**Returns:** `Promise<CurrentUser>`

**Throws:** Error if not authenticated

### `requireAdmin()`
Require admin access, throws error if not admin.

**Returns:** `Promise<CurrentUser>`

**Throws:** Error if not authenticated or not admin

## CurrentUser Interface

```typescript
interface CurrentUser {
  id: number
  email: string
  name: string | null
  role: string
  isActive: boolean
  isVerified: boolean
}
```

## Troubleshooting

### "Authentication required" error
- Check if user is logged in
- Verify JWT_SECRET is set correctly
- Check cookie is being sent with requests

### "Admin access required" error
- Verify user role in database is 'ADMIN'
- Check external auth API is returning correct role

### Middleware redirect loops
- Ensure login page is in `publicRoutes` array
- Check middleware matcher configuration

## Future Enhancements

- [ ] Refresh token mechanism
- [ ] Session management dashboard
- [ ] Multi-factor authentication (MFA)
- [ ] OAuth integration
- [ ] Rate limiting on login attempts
- [ ] Audit logging for authentication events
