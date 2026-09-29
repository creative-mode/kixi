'use client';

import { Input } from '@/components/ui/input';
import { Search } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useDebounce } from '@/hooks/use-debounce';

interface SearchInputProps {
    onSearch: (value: string) => void;
    placeholder?: string;
    className?: string;
}

export function SearchInput({ onSearch, placeholder = 'Pesquisar...', className }: SearchInputProps) {
    const [value, setValue] = useState('');
    const debouncedValue = useDebounce(value, 1000);

    useEffect(() => {
        onSearch(debouncedValue);
    }, [debouncedValue, onSearch]);

    return (
        <div className={`relative ${className}`}>
            <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 text-muted-foreground" size={18} />
            <Input
                placeholder={placeholder}
                value={value}
                onChange={(e) => setValue(e.target.value)}
                className="pl-10"
            />
        </div>
    );
}
