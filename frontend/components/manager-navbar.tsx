"use client";

import { useState, useEffect } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import {
  LayoutDashboard,
  FileText,
  Calendar,
  Image,
  UserCheck,
  Layers,
  Briefcase,
  Newspaper,
  LogOut,
  X,
  Menu as MenuIcon,
  ChevronRight,
  Quote,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { logoutAction } from "@/app/actions/auth";
import { UserDisplay } from "./user-display";

const navLinks = [
  {
    href: "/",
    label: "Dashboard",
    description: "Visão geral e métricas",
    icon: LayoutDashboard,
    color: "text-gray-900",
  },
  {
    href: "/school-year",
    label: "Anos Letivos",
    description: "Gestão de anos letivos",
    icon: Calendar,
    color: "text-gray-900",
  }
];

export function ManagerNavbar() {
  const [isOpen, setIsOpen] = useState(false);
  const pathname = usePathname();

  // Don't render navbar on public pages (login)
  const isPublicPage = pathname === '/login';

  // Close menu when route changes
  useEffect(() => {
    setIsOpen(false);
  }, [pathname]);

  // Lock body scroll when menu is open
  useEffect(() => {
    if (isOpen && !isPublicPage) {
      document.body.style.overflow = "hidden";
    } else {
      document.body.style.overflow = "unset";
    }
    return () => {
      document.body.style.overflow = "unset";
    };
  }, [isOpen, isPublicPage]);

  if (isPublicPage) return null;

  return (
    <>
      <header className="fixed top-0 left-0 right-0 z-50 border-b border-gray-200 bg-white/90 backdrop-blur-md">
        <div className="container mx-auto px-4 h-16 flex items-center justify-between">
          <Link
            href="/"
            className="text-xl font-bold tracking-tighter z-50 relative flex items-center gap-2"
          >
            <span className="text-[#05A649]">Kixi</span>{" "}
            <span className="font-display italic text-gray-700">Manager</span>
          </Link>

          <div className="flex items-center gap-4 z-50 relative">
            <div className="hidden md:block">
              <UserDisplay />
            </div>
            <button
              onClick={() => setIsOpen(!isOpen)}
              className="p-2 rounded-full hover:bg-muted transition-colors relative group"
            >
              <div className="relative w-6 h-6 flex items-center justify-center">
                <AnimatePresence mode="wait">
                  {isOpen ? (
                    <motion.div
                      key="close"
                      initial={{ rotate: -90, opacity: 0 }}
                      animate={{ rotate: 0, opacity: 1 }}
                      exit={{ rotate: 90, opacity: 0 }}
                      transition={{ duration: 0.2 }}
                    >
                      <X size={24} />
                    </motion.div>
                  ) : (
                    <motion.div
                      key="menu"
                      initial={{ rotate: 90, opacity: 0 }}
                      animate={{ rotate: 0, opacity: 1 }}
                      exit={{ rotate: -90, opacity: 0 }}
                      transition={{ duration: 0.2 }}
                    >
                      <MenuIcon size={24} />
                    </motion.div>
                  )}
                </AnimatePresence>
              </div>
            </button>
          </div>
        </div>
      </header>

      <AnimatePresence>
        {isOpen && (
          <motion.div
            initial={{ opacity: 0, y: "-100%" }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: "-100%" }}
            transition={{ type: "spring", damping: 25, stiffness: 200 }}
            className="fixed inset-0 z-40 bg-white/98 backdrop-blur-xl pt-24 pb-8 overflow-y-auto"
          >
            <div className="container mx-auto px-4">
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 md:gap-6 max-w-6xl mx-auto">
                {navLinks.map((link, index) => {
                  const Icon = link.icon;
                  const isActive = pathname === link.href;

                  return (
                    <motion.div
                      key={link.href}
                      initial={{ opacity: 0, y: 20 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={{ delay: index * 0.05 + 0.2 }}
                    >
                      <Link
                        href={link.href}
                        className={cn(
                          "group flex items-start gap-4 p-6 rounded-2xl border transition-all duration-300",
                          isActive
                            ? "bg-gray-50 border-gray-300 shadow-sm"
                            : "bg-white hover:bg-gray-50 border-gray-200 hover:border-gray-300 hover:shadow-md"
                        )}
                      >
                        <div
                          className={cn(
                            "p-3 rounded-xl bg-gray-100 shadow-sm group-hover:scale-110 transition-transform duration-300",
                            link.color
                          )}
                        >
                          <Icon size={24} />
                        </div>
                        <div className="flex-1">
                          <div className="flex items-center justify-between mb-1">
                            <span className="font-semibold text-lg">
                              {link.label}
                            </span>
                            <ChevronRight
                              size={16}
                              className="opacity-0 -translate-x-2 group-hover:opacity-100 group-hover:translate-x-0 transition-all duration-300 text-muted-foreground"
                            />
                          </div>
                          <p className="text-sm text-muted-foreground group-hover:text-foreground/80 transition-colors">
                            {link.description}
                          </p>
                        </div>
                      </Link>
                    </motion.div>
                  );
                })}
              </div>

              <motion.div
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                transition={{ delay: 0.6 }}
                className="mt-12 max-w-6xl mx-auto border-t pt-8 flex justify-between items-center"
              >
                <div className="md:hidden">
                  <UserDisplay />
                </div>
                <button
                  onClick={() => logoutAction()}
                  className="flex items-center gap-2 px-6 py-3 rounded-full bg-red-500/10 text-red-500 hover:bg-red-500 hover:text-white transition-all duration-300 font-medium ml-auto"
                >
                  <LogOut size={18} />
                  Sair do Sistema
                </button>
              </motion.div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </>
  );
}
