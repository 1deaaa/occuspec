/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./src/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      colors: {
        ink: "#0a1628",
        brand: {
          DEFAULT: "#0b5fff",
          dark: "#0849c4",
          light: "#e2ebfd",
        },
        line: "#dbe4f0",
        paper: "#f4f7fc",
        muted: "#5b6b82",
      },
    },
  },
  plugins: [],
}
