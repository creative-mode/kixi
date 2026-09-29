'use client'

import { useState, useRef } from 'react'
import { Upload, X, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { toast } from 'sonner'

interface ImageUploadProps {
    onUploadComplete: (url: string) => void
    currentImage?: string
    onRemove?: () => void
}

export function ImageUpload({ onUploadComplete, currentImage, onRemove }: ImageUploadProps) {
    const [isUploading, setIsUploading] = useState(false)
    const [preview, setPreview] = useState<string | null>(currentImage || null)
    const fileInputRef = useRef<HTMLInputElement>(null)

    const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0]
        if (!file) return

        // Validate file type
        if (!file.type.startsWith('image/')) {
            toast.error('Por favor, selecione apenas arquivos de imagem')
            return
        }

        // Validate file size (max 5MB)
        if (file.size > 5 * 1024 * 1024) {
            toast.error('A imagem deve ter no máximo 5MB')
            return
        }

        setIsUploading(true)

        try {
            // Create FormData
            const formData = new FormData()
            formData.append('file', file)
            formData.append('upload_preset', process.env.NEXT_PUBLIC_CLOUDINARY_UPLOAD_PRESET || 'techify_preset')

            // Upload to Cloudinary
            const response = await fetch(
                `https://api.cloudinary.com/v1_1/${process.env.NEXT_PUBLIC_CLOUDINARY_CLOUD_NAME}/image/upload`,
                {
                    method: 'POST',
                    body: formData,
                }
            )

            if (!response.ok) {
                throw new Error('Falha no upload')
            }

            const data = await response.json()
            const imageUrl = data.secure_url

            setPreview(imageUrl)
            onUploadComplete(imageUrl)
            toast.success('Imagem enviada com sucesso!')
        } catch (error) {
            console.error('Upload error:', error)
            toast.error('Erro ao enviar imagem. Tente novamente.')
        } finally {
            setIsUploading(false)
        }
    }

    const handleRemove = () => {
        setPreview(null)
        if (fileInputRef.current) {
            fileInputRef.current.value = ''
        }
        onRemove?.()
    }

    return (
        <div className="space-y-4">
            <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                onChange={handleFileChange}
                className="hidden"
                disabled={isUploading}
            />

            {preview ? (
                <div className="relative group">
                    <img
                        src={preview}
                        alt="Preview"
                        className="w-full h-48 object-cover rounded-lg border"
                    />
                    <button
                        type="button"
                        onClick={handleRemove}
                        className="absolute top-2 right-2 p-2 bg-destructive text-destructive-foreground rounded-full opacity-0 group-hover:opacity-100 transition-opacity"
                    >
                        <X size={16} />
                    </button>
                </div>
            ) : (
                <button
                    type="button"
                    onClick={() => fileInputRef.current?.click()}
                    disabled={isUploading}
                    className="w-full h-48 border-2 border-dashed border-border rounded-lg flex flex-col items-center justify-center gap-2 hover:border-foreground/50 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                >
                    {isUploading ? (
                        <>
                            <Loader2 className="animate-spin" size={32} />
                            <span className="text-sm text-muted-foreground">Enviando...</span>
                        </>
                    ) : (
                        <>
                            <Upload size={32} className="text-muted-foreground" />
                            <span className="text-sm text-muted-foreground">
                                Clique para enviar uma imagem
                            </span>
                            <span className="text-xs text-muted-foreground">
                                PNG, JPG, GIF até 5MB
                            </span>
                        </>
                    )}
                </button>
            )}
        </div>
    )
}
