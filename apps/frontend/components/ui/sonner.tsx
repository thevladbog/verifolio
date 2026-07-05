"use client";

import { Toaster as Sonner, type ToasterProps } from "sonner";

function Toaster(props: ToasterProps) {
  return (
    <Sonner
      position="bottom-right"
      toastOptions={{
        style: {
          background: "#ffffff",
          color: "#0f1b2e",
          border: "1px solid #e4e7ec",
          borderRadius: "12px",
          boxShadow: "0 16px 40px rgb(15 27 46 / 0.1)",
        },
      }}
      {...props}
    />
  );
}

export { Toaster };
