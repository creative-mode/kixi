# Configuração do Cloudinary

Para usar o upload de imagens, você precisa configurar as seguintes variáveis de ambiente no arquivo `.env`:

```env
# Cloudinary Configuration
NEXT_PUBLIC_CLOUDINARY_CLOUD_NAME=your_cloud_name
NEXT_PUBLIC_CLOUDINARY_UPLOAD_PRESET=your_upload_preset
CLOUDINARY_API_KEY=your_api_key
CLOUDINARY_API_SECRET=your_api_secret
```

## Como obter as credenciais do Cloudinary

1. Acesse [cloudinary.com](https://cloudinary.com) e crie uma conta gratuita
2. No Dashboard, você encontrará:
   - **Cloud Name**: Nome da sua cloud
   - **API Key**: Chave da API
   - **API Secret**: Segredo da API

3. Crie um Upload Preset:
   - Vá em Settings > Upload
   - Scroll até "Upload presets"
   - Clique em "Add upload preset"
   - Configure:
     - Preset name: `kixi_preset` (ou outro nome de sua escolha)
     - Signing Mode: **Unsigned**
     - Folder: `techify` (opcional)
   - Salve o preset

4. Adicione as variáveis no arquivo `.env`

## Estrutura de Pastas no Cloudinary

As imagens serão organizadas da seguinte forma:
- `/kixi/gallery` - Imagens da galeria
- `/kixi/posts` - Imagens de capa dos posts (futuro)

## Limites da Conta Gratuita

- 25 GB de armazenamento
- 25 GB de bandwidth mensal
- Transformações ilimitadas

Isso é mais que suficiente para começar!
