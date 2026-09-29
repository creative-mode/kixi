import { prisma } from '../lib/prisma';
import bcrypt from 'bcryptjs';

async function main() {
  const email = 'abner.lourenco@gmail.com';
  const name = 'Abner Lourenço';
  const password = 'abnerlouren';
  const hashedPassword = await bcrypt.hash(password, 10);

  await prisma.user.upsert({
    where: { email },
    update: {
      name,
      password: hashedPassword,
      role: 'ADMIN',
      isActive: true,
      isVerified: true,
    },
    create: {
      email,
      name,
      password: hashedPassword,
      role: 'ADMIN',
      isActive: true,
      isVerified: true,
    },
  });

  console.log('Usuário admin criado/atualizado com sucesso!');
  await prisma.$disconnect();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
