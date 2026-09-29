import { cn } from "@/lib/utils";

interface ShimmerSkeletonProps extends React.HTMLAttributes<HTMLDivElement> {
    className?: string;
}

export function ShimmerSkeleton({ className, ...props }: ShimmerSkeletonProps) {
    return (
        <div
            className={cn(
                "relative overflow-hidden bg-muted/50 rounded-md",
                className
            )}
            {...props}
        >
            <div className="absolute inset-0 -translate-x-full animate-[shimmer_2s_infinite] bg-gradient-to-r from-transparent via-white/20 to-transparent" />
        </div>
    );
}
