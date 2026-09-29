import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  /* config options here */
  enablePrerenderSourceMaps: false,
  productionBrowserSourceMaps: false,
  basePath: '/manager',

  async redirects() {
    return [
      {
        source: '/',
        destination: '/manager',
        basePath: false,
        permanent: false,
      },
    ];
  },

  async rewrites() {
    return [];
  },
  headers: async () => {
    return [
      {
        source: '/:path*',
        headers: [
          {
            key: 'Access-Control-Allow-Credentials',
            value: 'true',
          },
          {
            key: 'Access-Control-Allow-Origin',
            value: 'https://techify.ao',
          },
          {
            key: 'Access-Control-Allow-Methods',
            value: 'GET,OPTIONS,PATCH,DELETE,POST,PUT',
          },
          {
            key: 'Access-Control-Allow-Headers',
            value: 'X-CSRF-Token, X-Requested-With, Accept, Accept-Version, Content-Length, Content-MD5, Content-Type, Date, X-Api-Version',
          },
        ],
      },
    ];
  },
};

export default nextConfig;
